package com.obimon.obimon_mobile;

import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Vector;

import static com.obimon.obimon_mobile.ManageObimon.BROADCAST_REFRESH;
import static com.obimon.obimon_mobile.ManageObimon.ManageState.IDLE;

/**
 * Created by av on 2016.11.19..
 */

public class Bootloader implements Runnable {

    // HID BL data
    PicDevice picDevice = new PicDevice();

    MyTestService myTestService;


    @Override
    public void run() {
        Log.d("BL", "Upgrade thread started");

        ByteBuffer buffer = PacketGetInfo();
        int ret = myTestService.blWrite(buffer);
        if(ret<0) {
            Log.d("BL", "Error sending get info to device");
            return;
        }

        buffer = myTestService.blRead();
        if(buffer == null) {
            Log.d("BL", "Error reading info from device");
            return;
        }
        ParseBootInfo(buffer);

        FillBootInfo();

        ret = LoadHex();
        if(ret<0) {
            Log.d("BL", "LoadHex Error");
            return;
        }

        Log.d("BL", "LoadHex OK");

        Log.d("BL", "Erase Device");
        buffer = PacketErase();
        ret = myTestService.blWrite(buffer);
        if(ret<0) {
            Log.d("BL", "Error sending erase to device");
            return;
        }
        Log.d("BL", "Erase Device OK");

        WriteDevice();

        myTestService.resetAfterProgramming = true;

        Log.d("BL", "Reset Device");
        buffer = PacketResetDevice();
        myTestService.blWrite(buffer);

        myTestService.manageObimon.state = IDLE;
        myTestService.programmingInProgress = false;
        myTestService.programmingPercentage = 0;

//        MyActivity.myTestService.getBaseContext().runOnUiThread(new Runnable() {
//            @Override
//            public void run() {
//                Toast.makeText(MyActivity.myTestService.getBaseContext(), "Please wait for Obimon to reboot!", Toast.LENGTH_SHORT).show();
//            }
//        }

    }

    class MemoryRange {
        int type;
        long start;
        long end;
        long dataBufferLength;
        byte[] dataBuffer=null;
    }

    class DeviceData {
        public Vector<MemoryRange> ranges;
        DeviceData() {
            ranges = new Vector<>();
        }
    }

    // program memory region types
    static int PROGRAM_MEMORY = 0x01;
    static int EEPROM_MEMORY = 0x02;
    static int CONFIG_MEMORY = 0x03;
    static int USERID_MEMORY = 0x04;
    //#define EEPROM_RUNE_MEMORY          0x65
    //#define EEPROM_USER_PROGRAM_MEMORY  0x66
    static int END_OF_TYPES_LIST  = 0xFF;
    static int ALL_MEMORY_RANGES  = 0xFF;
    static int BOOTLOADER_V1_01_OR_NEWER_FLAG = 0xA5;   //Tacked on in region Type6 byte, to indicate when using newer version of bootloader with extended query info available

    int LoadHex()  {
        Log.d("BL", "LoadHex");

        InputStream inputStream = null;
        try {
            inputStream = MyActivity.context.getAssets().open("obimon.X.production.hex");
        } catch (IOException e) {
            e.printStackTrace();
            Log.d("BL", "Cannot open HEX file");
            return -1;
        }
        BufferedReader in = new BufferedReader(new InputStreamReader(inputStream));

        int byteCount;
        long segmentAddress=0;
        long lineAddress;
        long recordType;
        long deviceAddress;
        boolean hasConfigBits=false;

        int DATA = 0x00;
        int END_OF_FILE = 0x01;
        int EXTENDED_SEGMENT_ADDR = 0x02;
        int EXTENDED_LINEAR_ADDR = 0x04;

        String line;
        try {
            while((line = in.readLine()) != null) {
                //Log.d("BL", "Hex: "+line);

                //Do some error checking on the .hex file contents, to make sure the file is
                //formatted like a legitimate Intel 32-bit formatted .hex file.
                if ((line.charAt(0) != ':') || (line.length() < 11)) {
                    Log.d("BL", "Error in line:" +line);
                    return -1;
                }

                //Extract the info prefix fields from the hex file line data.
                //Example Intel 32-bit hex file line format is as follows (Note: spaces added to separate fields, actual line does not have spaces in it):
                //: 10 0100 00 214601360121470136007EFE09D21901 40
                //Leading ":" is always present on each line from the .hex file.
                //Next two chars (10) are the byte count of the data payload on this hex file line. (ex: 10 = 0x10 = 16 bytes)
                //Next four chars (0100) are the 16 bit address (needs to be combined with the extended linear address to generate a 32-bit address).
                //Next two chars (00) are the "record type".  "00" means it is a "data" record, which means it contains programmable payload data bytes.
                //Next 2n characters are the data payload bytes (where n is the number of bytes specified in the first two numbers (10 in this example))
                //Last two characters on the line are the two complement of the byte checksum computed on the other bytes in the line.
                //For more details on Intel 32-bit hex file formatting see: http://en.wikipedia.org/wiki/Intel_HEX

                byteCount = Integer.parseInt(line.substring(1,3), 16);
                lineAddress = segmentAddress + Long.parseLong(line.substring(3,7), 16);   //Convert the four ASCII chars that correspond to the line address into a 16-bit binary encoded word
                recordType = Long.parseLong(line.substring(7,9), 16);       //Convert the two ASCII chars corresponding to the record type into a binary

                //Log.d("BL", "byteCount "+byteCount+" lineAddress "+lineAddress+" recordType "+recordType);

                //Error check: Verify checksum byte at the end of the .hex file line is valid.  Note,
                //this is not the same checksum as MPLAB(R) IDE uses/computes for the entire hex file.
                //This is only the mini-checksum at the end of each line in the .hex file.
                long hexLineChecksum = 0;

                for(int i = 0; i < (byteCount+4); i++)  //+4 correction is for byte count, 16-bit address, and record type bytes
                {
                    String h = line.substring(1+(2*i), 1+(2*i)+2);
                    long wordByte = Long.parseLong(h, 16); //Fetch two adjacent ASCII bytes from the .hex file

                    //Add the newly fetched byte to the running checksum
                    hexLineChecksum += wordByte;
                }

                //Now get the two's complement of the hexLineChecksum.
                hexLineChecksum = 0 - hexLineChecksum;
                hexLineChecksum &= 0xFF;    //Truncate to a single byte.  We now have our computed checksum.  This should match the .hex file.

                //Fetch checksum byte from the .hex file
                long wordByte = Long.parseLong(line.substring(1 + (2 * (byteCount+4))), 16);     //Fetch the two ASCII bytes that correspond to the checksum byte
                wordByte &= 0xFF;

                //Now check if the checksum we computed matches the one at the end of the line in the hex file.
                if(hexLineChecksum != wordByte) {
                    Log.d("BL", "Wrong checksum "+hexLineChecksum+" "+wordByte);
                    return -2;
                }

                //Check the record type of the hex line, to determine how to continue parsing the data.

                if (recordType == END_OF_FILE)                        // end of file record
                {
                    Log.d("BL", "End of file found");
                    //hasEndOfFileRecord = true;
                    break;
                }

                if ((recordType == EXTENDED_SEGMENT_ADDR) || (recordType == EXTENDED_LINEAR_ADDR)) // Segment address
                {
                    //Error check: Make sure the line contains the correct number of bytes for the specified record type
                    if (line.length() >= (11 + (2 * byteCount))) {

                        //Fetch the payload, which is the upper 4 or 16-bits of the 20-bit or 32-bit hex file address
                        // segmentAddress = line.mid(9, 4).toInt(&ok, 16);
                        String s = line.substring(9, 13);
                        segmentAddress = Long.parseLong(s, 16);

                        //Log.d("BL", "seg "+s);

                        //Load the upper bits of the address
                        if (recordType == EXTENDED_SEGMENT_ADDR) {
                            segmentAddress <<= 4;
                        } else {
                            segmentAddress <<= 16;
                        }

                        //Update the line address, now that we know the upper bits are something new.
                        //lineAddress = segmentAddress + line.mid(3, 4).toInt(&ok, 16);
                        s = line.substring(3, 7);
                        //Log.d("BL", "lin "+s);

                        lineAddress = segmentAddress + Long.parseLong(s, 16);

                        //Log.d("BL", "Extended record segmentAddress " + segmentAddress + " lineAddress " + lineAddress);
                    } else {
                        //Length appears to be wrong in hex line entry.
                        return -3;
                    }
                }

                if (recordType == DATA)                        // Data Record
                {
                    //Error check to make sure line is long enough to be consistent with the specified record type
                    if (line.length() < (11 + (2 * byteCount))) {
                        Log.d("BL", "DATA is wrong length");
                        return -5;
                    }

                    //For each data payload byte we find in the hex file line, check if it is contained within
                    //a progammable region inside the microcontroller.  If so save it.  If not, discard it.
                    for (int i = 0; i < byteCount; i++) {
                        //Fetch ASCII encoded payload byte from .hex file and save the byte to our temporary RAM buffer.
                        String hexByte = line.substring(9 + (2 * i), 9 + (2 * i) + 2);  //Fetch two ASCII data payload bytes from the .hex file
                        wordByte = Integer.parseInt(hexByte, 16);   //Re-format the above two ASCII bytes into a single binary encoded byte (0x00-0xFF)

                        //Use the hex file linear byte address, to compute other imformation about the
                        //byte/location.  The GetDeviceAddressFromHexAddress() function gives us a pointer to
                        //the PC RAM buffer byte that will get programmed into the microcontroller, which corresponds
                        //to the specified .hex file extended address.
                        //The function also returns a boolean letting us know if the address is part of a programmable memory region on the device.

                        int ret = getDeviceAddressFromHexAddress(lineAddress + i, (byte)wordByte);
                        if (ret < 0) {
                            Log.d("BL", "Insufficient memory");
                            return -1;
                        }

                        if (ret == 1) {
                            hasConfigBits = true;
                        }

                    }//for(i = 0; i < byteCount; i++)
                }

            } // end if ((recordType == EXTENDED_SEGMENT_ADDR) || (recordType == EXTENDED_LINEAR_ADDR)) // Segment address

        } catch (IOException e) {
            e.printStackTrace();
            return -1;
        }

        return 0;
    }

    class PicDevice {
        int bytesPerPacket ;
        int deviceFamily ;

        int bytesPerWordFLASH;
        int bytesPerWordEEPROM;
        int bytesPerWordConfig;
        int bytesPerAddressFLASH;
        int bytesPerAddressEEPROM;
        int bytesPerAddressConfig;

        DeviceData deviceData = new DeviceData();
    }

    class MemoryRegion {
        int type;
        long address;
        long size;
    }

    byte QUERY_DEVICE = 0x02;
    ByteBuffer PacketGetInfo() {
        ByteBuffer buffer = ByteBuffer.allocate(64);
        buffer.put(0, QUERY_DEVICE); // command
        return buffer;
    }

    byte ERASE_DEVICE = 0x04;
    ByteBuffer PacketErase() {
        ByteBuffer buffer = ByteBuffer.allocate(64);
        buffer.put(0, ERASE_DEVICE); // command
        return buffer;
    }

    byte RESET_DEVICE = 0x08;
    ByteBuffer PacketResetDevice() {
        ByteBuffer buffer = ByteBuffer.allocate(64);
        buffer.put(0, RESET_DEVICE); // command
        return buffer;
    }

    void ReadPacket(ByteBuffer bb) {
        byte command = bb.get();
        long address = bb.getInt();
        byte bytesPerPacket = bb.get();

        //    unsigned char command;
        //    unsigned long int address;
        //    unsigned char bytesPerPacket;
        //    unsigned char data[59];

        //if(bootInfo->command != 0x02) {
        //    qWarning("Received incorrect command.");
        //    return IncorrectCommand;
        //}

    }



//    struct WritePacket
//    {
//        unsigned char report;
//        unsigned char command;
//        union {
//        unsigned long int address;
//        unsigned char LockedValue;
//    };
//        unsigned char bytesPerPacket;
//        union {
//        unsigned char data[58];
//        struct {
//            unsigned char pad1[42];
//            unsigned char protectionPassword[8];
//            unsigned char pad2[8];
//        }BootInfo;
//    };
//    };
//    struct ReadPacket
//    {
//        unsigned char command;
//        unsigned long int address;
//        unsigned char bytesPerPacket;
//        unsigned char data[59];
//    };


    //This method is useful for hex file parsing.  This function checks if the address indicated in the hex
    //file line is contained in one of the programmable regions (ex: flash, eeprom, config words, etc.), as
    //specified by the USB device, (from the query response that we received earlier).  If the address
    //from the hex file is contained in the programmage device address range, this function returns
    //bool includedInProgrammableRange = true, with the type set to the programmable region type
    //(which can be determined based on the address and the query response).  This function also returns
    //the new effective device address (not from the .hex file, which is a raw linear byte address, but the
    //effective address the device should load into its self programming address registers, in order to
    //program the contents [PIC24 uses a 16-bit word addressed flash array, so the hex file addresses
    //don't match the microcontroller addresses]).
    //This function also returns bool addressWasEndofRange = true, if the input hexAddress corresponded
    //to the very last byte of the last address of the programmable memory region that the hexAddress
    //corresponded to.  Otherwise this value returns false.  This provides an easy check later to
    //know when an end of a region has been completed during hex file parsing.
    int getDeviceAddressFromHexAddress(long hexAddress, byte wordByte) {
        //Log.d("BL", "getDeviceAddressFromHexAddress "+hexAddress+" "+wordByte );

        long flashAddress = hexAddress / picDevice.bytesPerAddressFLASH;
        long eepromAddress = hexAddress / picDevice.bytesPerAddressEEPROM;
        long configAddress = hexAddress / picDevice.bytesPerAddressConfig;

        byte[] pRAMDataBuffer;

        long byteOffset;

        //Loop for each of the previously identified programmable regions, based on the results of the
        //previous Query device response packet.

        for(MemoryRange range : picDevice.deviceData.ranges) {

            //Find what address range the hex address seems to contained within (if any, could be none, in
            //the case the .hex file contains info that is not part of the bootloader re-programmable region of flash).

            if ((range.type == PROGRAM_MEMORY) && (flashAddress >= range.start) && (flashAddress < range.end)) {
                if (range.start != 0) {
                    byteOffset = ((flashAddress - range.start) * picDevice.bytesPerAddressFLASH) + (hexAddress % picDevice.bytesPerAddressFLASH);
                    range.dataBuffer[(int) byteOffset] = wordByte;
                    //Log.d("BL", "Full range data buffer "+byteOffset+" "+wordByte);
                    return 0;
                }

                Log.d("BL", "PROGRAM_MEMORY range.start is zero");
                return -1;
            }


            if ((range.type == EEPROM_MEMORY) && (eepromAddress >= range.start) && (eepromAddress < range.end)) {
                if (range.start != 0) {
                    byteOffset = ((eepromAddress - range.start) * picDevice.bytesPerAddressEEPROM) + (hexAddress % picDevice.bytesPerAddressEEPROM);
                    range.dataBuffer[(int) byteOffset] = wordByte;
                    return 0;
                }
                Log.d("BL", "EEPROM_MEMORY range.start is zero");
                return -1;
            }

            if ((range.type == CONFIG_MEMORY) && (configAddress >= range.start) && (configAddress < range.end)) {
                if (range.start != 0) {
                    byteOffset = ((configAddress - range.start) * picDevice.bytesPerAddressConfig) + (hexAddress % picDevice.bytesPerAddressConfig);
                    range.dataBuffer[(int) byteOffset] = wordByte;
                    return 1; // CONFIG BITS!
                }
                Log.d("BL", "CONFIG_MEMORY range.start is zero");
                return -1;
            }
        }

        //Log.d("BL", "Did not find range for address "+hexAddress);

        return 0;
    }

    class WritePacket {
        byte command;
        long address;
        int bytesPerPacket;
        byte[] data=null;
    }

    byte PROGRAM_DEVICE = 0x05;
    byte PROGRAM_COMPLETE = 0x06;
    int Program(int address, int bytesPerPacket, int bytesPerAddress, int bytesPerWord, int endAddress, byte[] pData) {
        Log.d("BL", "Program bytesPerPacket:"+bytesPerPacket+" bytesPerAddress:"+bytesPerAddress+" bytesPerWord:"+bytesPerWord+" endAddress:"+endAddress+" pData len "+pData.length);

        String s="";
        for(int i=0; i<100; i++) s += String.format("%02x", pData[i])+" ";
        Log.d("BL", s);

        WritePacket writePacket = new WritePacket();

        int i;
        boolean allPayloadBytesFF;
        boolean firstAllFFPacketFound = false;
        int bytesToSend;

        byte lastCommandSent = PROGRAM_DEVICE;

        float percentCompletion;
        long addressesToProgram;
        int startOfDataPayloadIndex;

        int pDataOffset=0;
        int result=0;

        //Error check input parameters before using them
        if((pData == null) || (bytesPerAddress == 0) || (address > endAddress) || (bytesPerWord == 0))
        {
            Log.d("BL", "Bad parameters specified when calling Program() function.");
            return -1;
        }

        //Error check to make sure the requested maximum data payload size is an exact multiple of the bytesPerAddress.
        //If not, shrink the number of bytes we actually send, so that it is always an exact multiple of the
        //programmable media bytesPerAddress.  This ensures that we don't "half" program any memory address (ex: if each
        //flash address is a 16-bit word address, we don't want to only program one byte of the address, we want to program
        //both bytes.

        while((bytesPerPacket % bytesPerWord) != 0)
        {
            bytesPerPacket--;
        }

        //Setup variable, used for progress bar updating computations.
        addressesToProgram = endAddress - address;
        if(addressesToProgram == 0) //Check to avoid potential divide by zero later.
            addressesToProgram++;

        //Loop through the entire data set/region, but break it into individual packets before sending it
        //to the device.

        while(address < endAddress)
        {
            //Update the progress bar so the user knows things are happening.
            percentCompletion = 100*((float)1 - (float)((float)(endAddress - address)/(float)addressesToProgram));
            if(percentCompletion > 100)
            {
                percentCompletion = 100;
            }
            Log.d("BL", "Percent "+percentCompletion);
            myTestService.programmingPercentage = (int)percentCompletion;
            LocalBroadcastManager.getInstance(myTestService).sendBroadcast(new Intent(BROADCAST_REFRESH));


            //Prepare the packet to send to the device.
            writePacket.command = PROGRAM_DEVICE;
            writePacket.address = address;

            //Check if we are near the end of the programmable region, and need to send a "short packet" (with less than the maximum
            //allowed program data payload bytes).  In this case we need to notify the device by using the PROGRAM_COMPLETE command instead
            //of the normal PROGRAM_DEVICE command.  This lets the bootloader firmware in the device know it should flush any internal
            //buffers it may be using, by programming all of the bufferred data to NVM memory.

            writePacket.data = new byte[58];
            Arrays.fill( writePacket.data, (byte)0xff );

            if(((endAddress - address) * bytesPerAddress) < bytesPerPacket)
            {

                writePacket.bytesPerPacket = (endAddress - address) * bytesPerAddress;

                //Copy the packet data to the actual outgoing buffer and then send it over USB to the device.
                System.arraycopy(pData, pDataOffset, writePacket.data, 58 - writePacket.bytesPerPacket, writePacket.bytesPerPacket);
                //memcpy((unsigned char*)&writePacket.data[0] + 58 - writePacket.bytesPerPacket, pData, writePacket.bytesPerPacket);

                //Check to make sure we are completely programming all bytes of the destination address.  If not,
                //increase the data size and set the extra byte(s) to 0xFF (the default/blank value).
                while((writePacket.bytesPerPacket % bytesPerWord) != 0)
                {
                    if(writePacket.bytesPerPacket >= bytesPerPacket)
                    {
                        break; //should never hit this break, due to while((bytesPerPacket % bytesPerWord) != 0) check at start of function
                    }

                    //Shift all the data payload bytes in the packet to the left one (lower address),
                    //so we can insert a new 0xFF byte.
                    for(i = 0; i < bytesPerPacket - 1; i++)
                    {
                        writePacket.data[i] = writePacket.data[i+1];
                    }
                    writePacket.data[writePacket.bytesPerPacket] = (byte)0xFF;
                    writePacket.bytesPerPacket++;
                }

                bytesToSend = writePacket.bytesPerPacket;

                Log.d("BL", "Preparing short packet of final program data with payload: 0x%x");
            }
            else
            {
                //Else we are planning on sending a full length packet with the full size payload.
                writePacket.bytesPerPacket = bytesPerPacket;
                bytesToSend = bytesPerPacket;

                //Copy the packet data to the actual outgoing buffer and then prepare to send it.
                System.arraycopy(pData, pDataOffset, writePacket.data, 58 - writePacket.bytesPerPacket, writePacket.bytesPerPacket);
                //memcpy((unsigned char*)&writePacket.data[0] + 58 - writePacket.bytesPerPacket, pData, writePacket.bytesPerPacket);
            }

            //Check if all bytes of the data payload section of the packet are == 0xFF.  If so, we can save programming
            //time by skipping the packet by not sending it to the device.  The default/erased value is already = 0xFF, so
            //the contents of the flash memory will be correct (although if we want to be certain we should make sure
            //the 0xFF regions are still getting checked during the verify step, in case the erase procedure failed to set
            //all bytes = 0xFF).

            allPayloadBytesFF = true;   //assume true until we do the actual check below

            //Loop for all of the bytes in the data payload portion of the writePacket.  The data payload is little endian but is stored
            //"right justified" in the packet.  Therefore, writePacket.data[0] isn't necessarily the LSB data byte in the packet.

            startOfDataPayloadIndex = 58 - writePacket.bytesPerPacket;
            for(i = startOfDataPayloadIndex; i < (startOfDataPayloadIndex + writePacket.bytesPerPacket); i++)
            {
                if(writePacket.data[i] != 0xFF)
                {
                    //Special check for PIC24, where every 4th byte from the .hex file is == 0x00,
                    //which is the "phantom byte" (the upper byte of each odd address 16-bit word
                    //is unimplemented, and is probably 0x00 in the .hex file).

                    if(((i - startOfDataPayloadIndex) % bytesPerWord) == 3)
                    {
                        //We can ignore this "phantom byte", since it is unimplemented and effectively a "don't care" byte.
                    }
                    else
                    {
                        //We found a non 0xFF (or blank value) byte.  We need to send and program the
                        //packet of useful data into the device.

                        allPayloadBytesFF = false;
                        break;
                    }
                }
            }

            //Check if we need to send a normal packet of data to the device, if the packet was all 0xFF and
            //we need to send a PROGRAM_COMPLETE packet, or if it was all 0xFF and we can simply skip it without
            //doing anything.
            if(allPayloadBytesFF == false)
            {

                //Log.d("BL", "Sending program data packet with address "+writePacket.address);

                //We need to send a normal PROGRAM_DEVICE packet worth of data to program.
                result = SendWritePacket(writePacket);

                //Verify the data was successfully received by the USB device.

                if(result != 0)
                {
                    Log.d("BL", "Error during program sending packet with address "+writePacket.address);
                    return -1;
                }

                firstAllFFPacketFound = true; //reset flag so it will be true the next time a pure 0xFF packet is found

                lastCommandSent = PROGRAM_DEVICE;
            }

            else if((allPayloadBytesFF == true) && (firstAllFFPacketFound == true))
            {
                //In this case we need to send a PROGRAM_COMPLETE command to let the firmware know it should flush
                //its buffer by programming all of it to flash, since we are about to skip to a new address range.

                writePacket.command = PROGRAM_COMPLETE;
                writePacket.bytesPerPacket = 0;

                firstAllFFPacketFound = false;

                Log.d("BL", "Sending program complete data packet to skip a packet with address: "+writePacket.address);
                result = SendWritePacket(writePacket);

                if(result != 0)
                {
                    Log.d("BL", "Error during program sending packet with address: "+writePacket.address);
                    return result;
                }

                lastCommandSent = PROGRAM_COMPLETE;
            }
            else
            {
                //If we get to here, this means that (allPayloadBytesFF == true) && (firstAllFFPacketFound == false).
                //In this case, the last packet that we processed was all 0xFF, and all bytes of this packet are
                //also all 0xFF.  In this case, we don't need to send any packet data to the device.  All we need
                //to do is advance our pointers and keep checking for a new non-0xFF section.

                Log.d("BL", "Skipping data packet with all 0xFF with address: "+writePacket.address);
            }

            //Increment pointers now that we successfully programmed (or deliberately skipped) a packet worth of data
            address += bytesPerPacket / bytesPerAddress;
            pDataOffset += bytesToSend;

            //Check if we just now exactly finished programming the memory region (in which case address will be exactly == endAddress)
            //region. (ex: we sent a PROGRAM_DEVICE instead of PROGRAM_COMPLETE for the last packet sent).
            //In this case, we still need to send the PROGRAM_COMPLETE command to let the firmware know that it is done,
            //and will not be receiving any subsequent program packets for this memory region.

            if(address >= endAddress)
            {
                //Check if we still need to send a PROGRAM_COMPLETE command (we don't need to send one if
                //the last command we sent was a PRORAM_COMPLETE already).

                if(lastCommandSent == PROGRAM_COMPLETE)
                {
                    break;
                }

                //memset((void*)&writePacket, 0x00, sizeof(writePacket));
                Arrays.fill( writePacket.data, (byte)0x00 );

                writePacket.command = PROGRAM_COMPLETE;
                writePacket.bytesPerPacket = 0;

                Log.d("BL", "Sending final program complete command for this region.");

                result = SendWritePacket(writePacket);

                return result;

            }

        }//while(address < endAddress)

        return result;
    }

    int SendWritePacket(WritePacket writePacket) {

        ByteBuffer buffer = ByteBuffer.allocate(64);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        buffer.put(writePacket.command); // command
        buffer.putInt((int)writePacket.address);
        buffer.put((byte)writePacket.bytesPerPacket);
        buffer.put(writePacket.data);

        //Log.d("BL", "WritePacket command: "+writePacket.command+" address:"+writePacket.address+" data size "+writePacket.data.length);

        //String s="";
        //for(int i=0; i<writePacket.data.length; i++) s += String.format("%02x", writePacket.data[i])+" ";
        //Log.d("BL", s);

        return MyActivity.myTestService.blWrite(buffer);
    }

    //This thread programs previously parsed .hex file data into the device's programmable memory regions.
    void WriteDevice() {
        int result;

        //Now being re-programming each section based on the info we obtained when
        //we parsed the user's .hex file.
        Log.d("BL", "Writing Device...");

        for (MemoryRange range : picDevice.deviceData.ranges)
        {
            if (range.type == PROGRAM_MEMORY)
            {
                Log.d("BL", "Writing Program memory ...");

                result = Program((int)range.start,
                        (int)picDevice.bytesPerPacket,
                        (int)picDevice.bytesPerAddressFLASH,
                        (int)picDevice.bytesPerWordFLASH,
                        (int)range.end,
                        range.dataBuffer);

                return ;
            }
            else if (range.type == EEPROM_MEMORY)
            {
                Log.d("BL", "Writing Eeprom ...");

                result = Program((int)range.start,
                        picDevice.bytesPerPacket,
                        picDevice.bytesPerAddressEEPROM,
                        picDevice.bytesPerWordEEPROM,
                        (int)range.end,
                        range.dataBuffer);

            }
            else if (range.type == CONFIG_MEMORY)
            {

                Log.d("BL", "Writing Device Config Words...");
                result = Program((int)range.start,
                        picDevice.bytesPerPacket,
                        picDevice.bytesPerAddressConfig,
                        picDevice.bytesPerWordConfig,
                        (int)range.end,
                        range.dataBuffer);
            }
            else
            {
                continue;
            }


            //IoWithDeviceCompleted("Writing", result, ((double)elapsed.elapsed()) / 1000);

            if(result != 0)
            {
                Log.d("BL","Programming failed");
                return;
            }

        }

        Log.d("BL", "Write Completed");

        //VerifyDevice();
    }

    // Since we know our device is PIC24f64gb... we can fill these data ourselves
    // THIS IS ONLY VALID IF WE KEEP THE CURRENT BOOTLOADER ADDRESSES (0x1800)
    void FillBootInfo() {

        picDevice.bytesPerPacket = 56;

        int PIC24 = 0x02;
        picDevice.deviceFamily = PIC24;

        picDevice.bytesPerAddressEEPROM = 1;
        picDevice.bytesPerAddressConfig = 1;
        picDevice.bytesPerWordEEPROM = 1;

        picDevice.bytesPerWordFLASH = 4;
        picDevice.bytesPerAddressFLASH = 2;
        picDevice.bytesPerWordConfig = 4;
        picDevice.bytesPerAddressConfig = 2;

        MemoryRegion memoryRegion = new MemoryRegion();

        memoryRegion.type = PROGRAM_MEMORY;
        memoryRegion.address = 6144;
        memoryRegion.size = 36864;

        MemoryRange range = new MemoryRange();
        range.type = PROGRAM_MEMORY;
        range.dataBufferLength = memoryRegion.size * picDevice.bytesPerAddressFLASH;
        range.dataBuffer = new byte[(int)range.dataBufferLength];
        Arrays.fill( range.dataBuffer, (byte)0xff );

        range.start = memoryRegion.address;
        range.end = memoryRegion.address + memoryRegion.size;

        //Add the new structure+buffer to the list
        picDevice.deviceData.ranges.add(range);
    }

    int ParseBootInfo(ByteBuffer bb) {
        bb.rewind();
        bb.order(ByteOrder.LITTLE_ENDIAN);

        int command = bb.get();
        if(command != 2) {
            Log.d("BL", "Wrong packet");
            return -1;
        }

        picDevice.bytesPerPacket = bb.get();
        Log.d("BL", "BytesPerPacket "+picDevice.bytesPerPacket);

        picDevice.deviceFamily = bb.get();

        int PIC24 = 0x02;
        if(picDevice.deviceFamily != PIC24) {
            Log.d("BL", "Wrong device family not PIC24 "+picDevice.deviceFamily);
            return -1;
        }

        Log.d("BL", "ProcessBootInfo OK");


        //Now start parsing the bootInfo packet to learn more about the device.  The bootInfo packet contains
        //contains the query response data from the USB device.  We will save these values into globabl variables
        //so other parts of the application can use the info when deciding how to do things.

        //Set some processor family specific global variables that will be used elsewhere (ex: during program/verify operations).

        picDevice.bytesPerAddressEEPROM = 1;
        picDevice.bytesPerAddressConfig = 1;
        picDevice.bytesPerWordEEPROM = 1;

        picDevice.bytesPerWordFLASH = 4;
        picDevice.bytesPerAddressFLASH = 2;
        picDevice.bytesPerWordConfig = 4;
        picDevice.bytesPerAddressConfig = 2;

        //Initialize the deviceData buffers and length variables, with the regions that the firmware claims are
        //reprogrammable.  We will need this information later, to decide what part(s) of the .hex file we
        //should look at/try to program into the device.  Data sections in the .hex file that are not included
        //in these regions should be ignored.

        int MAX_DATA_REGIONS = 0x06;

        Vector<MemoryRegion> memoryRegions = new Vector<>();
        for(int i=0; i<MAX_DATA_REGIONS; i++) {
            MemoryRegion memoryRegion = new MemoryRegion();
            memoryRegions.add(memoryRegion);

            memoryRegion.type = bb.get() & 0xFF;
            memoryRegion.address = (long)bb.getInt() & 0xffffffffL;
            memoryRegion.size = (long)bb.getInt() & 0xffffffffL;

            Log.d("BL", "MemoryRegion["+i+"] type "+memoryRegion.type+" addr "+memoryRegion.address+" size "+memoryRegion.size);
        }

        int versionFlag = bb.get();

        for(int i = 0; i < MAX_DATA_REGIONS; i++)
        {

            if(memoryRegions.get(i).type == END_OF_TYPES_LIST)
            {
                Log.d("BL", "END_OF_TYPES_LIST");

                //Before we quit, check the special versionFlag byte,
                //to see if the bootloader firmware is at least version 1.01.
                //If it is, then it will support the extended query command.
                //If the device is based on v1.00 bootloader firmware, it will have
                //loaded the versionFlag location with 0x00, which was a pad byte.

                if(versionFlag == BOOTLOADER_V1_01_OR_NEWER_FLAG)
                {
                    Log.d("BL", "Device bootloader firmware is v1.01 or newer and supports Extended Query.");

                    //Now fetch the extended query information packet from the USB firmware.
                    //comm->ReadExtendedQueryInfo(&extendedBootInfo);

                    //qDebug("Device bootloader firmware version is: " + extendedBootInfo.PIC18.bootloaderVersion);

                }

                break;

            }

            //Error check: Check the firmware's reported size to make sure it is sensible.  This ensures
            //we don't try to allocate ourselves a massive amount of RAM (capable of crashing this PC app)
            //if the firmware claimed an improper value.

            long MAXIMUM_PROGRAMMABLE_MEMORY_SEGMENT_SIZE = 0x0FFFFFFF;
            if(memoryRegions.get(i).size > MAXIMUM_PROGRAMMABLE_MEMORY_SEGMENT_SIZE)
            {
                Log.d("BL", "Exceeded maximum programmable memory segment size ============================");
                memoryRegions.get(i).size = MAXIMUM_PROGRAMMABLE_MEMORY_SEGMENT_SIZE;
            }

            MemoryRange range = new MemoryRange();

            //Parse the bootInfo response packet and allocate ourselves some RAM to hold the eventual data to program.
            if(memoryRegions.get(i).type == PROGRAM_MEMORY)
            {
                range.type = PROGRAM_MEMORY;
                range.dataBufferLength = memoryRegions.get(i).size * picDevice.bytesPerAddressFLASH;
                range.dataBuffer = new byte[(int)range.dataBufferLength];
                Arrays.fill( range.dataBuffer, (byte)0xff );

                Log.d("BL", "Initializing region for PROGRAM_MEMORY memory. Length:"+range.dataBufferLength);
            }
            else if(memoryRegions.get(i).type == EEPROM_MEMORY)
            {
                Log.d("BL", "Initializing region for EEPROM_MEMORY memory");
                range.type = EEPROM_MEMORY;
                range.dataBufferLength = memoryRegions.get(i).size * picDevice.bytesPerAddressEEPROM;
                range.dataBuffer = new byte[(int)range.dataBufferLength];
                Arrays.fill( range.dataBuffer, (byte)0xff );
            }

            else if(memoryRegions.get(i).type == CONFIG_MEMORY)
            {
                Log.d("BL", "Initializing region for CONFIG_MEMORY memory");
                range.type = CONFIG_MEMORY;
                range.dataBufferLength = memoryRegions.get(i).size * picDevice.bytesPerAddressConfig;
                range.dataBuffer = new byte[(int)range.dataBufferLength];
                Arrays.fill( range.dataBuffer, (byte)0xff );
            }

            //Notes regarding range.start and range.end: The range.start is defined as the starting address inside
            //the USB device that will get programmed.  For example, if the bootloader occupies 0x000-0xFFF flash
            //memory addresses (ex: on a PIC18), then the starting bootloader programmable address would typically
            //be = 0x1000 (ex: range.start = 0x1000).
            //The range.end is defined as the last address that actually gets programmed, plus one, in this programmable
            //region.  For example, for a 64kB PIC18 microcontroller, the last implemented flash memory address
            //is 0xFFFF.  If the last 1024 bytes are reserved by the bootloader (since that last page contains the config
            //bits for instance), then the bootloader firmware may only allow the last address to be programmed to
            //be = 0xFBFF.  In this scenario, the range.end value would be = 0xFBFF + 1 = 0xFC00.
            //When this application uses the range.end value, it should be aware that the actual address limit of
            //range.end does not actually get programmed into the device, but the address just below it does.
            //In this example, the programmed region would end up being 0x1000-0xFBFF (even though range.end = 0xFC00).
            //The proper code to program this would basically be something like this:

            //for(i = range.start; i < range.end; i++)
            //{
            //    //Insert code here that progams one device address.  Note: for PIC18 this will be one byte for flash memory.
            //    //For PIC24 this is actually 2 bytes, since the flash memory is addressed as a 16-bit word array.
            //}

            //In the above example, the for() loop exits just before the actual range.end value itself is programmed.



            range.start = memoryRegions.get(i).address;
            range.end = memoryRegions.get(i).address + memoryRegions.get(i).size;

            //Add the new structure+buffer to the list
            picDevice.deviceData.ranges.add(range);

        }

        return 0;
    }
//    //Routine that verifies the contents of the non-voltaile memory regions in the device, after an erase/programming cycle.
//    //This function requests the memory contents of the device, then compares it against the parsed .hex file data to make sure
//    //The locations that got programmed properly match.
//    int VerifyDevice()
//    {
//
//        int result;
//
//        int i, j;
//        int arrayIndex;
//        boolean failureDetected = false;
//
//        byte[] flashData= new byte[MAX_ERASE_BLOCK_SIZE];
//        byte[] hexEraseBlockData = new byte[MAX_ERASE_BLOCK_SIZE];
//
//        long startOfEraseBlock;
//
//        //Initialize an erase block sized buffer with 0xFF.
//        //Used later for post SIGN_FLASH verify operation.
//        Arrays.fill(hexEraseBlockData, (byte)0xff);
//        //memset(&hexEraseBlockData[0], 0xFF, MAX_ERASE_BLOCK_SIZE);
//
//        Log.d("BL", "Verifying Device...");
//
//        for(MemoryRange deviceRange : picDevice.deviceData.ranges) {
//
//            if(deviceRange.type == picDevice.deviceData.PROGRAM_MEMORY)
//            {
//                result = comm->GetData(deviceRange.start,
//                        device->bytesPerPacket,
//                        device->bytesPerAddressFLASH,
//                        device->bytesPerWordFLASH,
//                        deviceRange.end,
//                        deviceRange.pDataBuffer);
//
//
//                if(result != 0)
//                {
//                    Log.d("BL", "Error in GetData");
//                    return -1;
//                }
//
//                //Search through all of the programmable memory regions from the parsed .hex file data.
//                //For each of the programmable memory regions found, if the region also overlaps a region
//                //that was included in the device programmed area (which just got read back with GetData()),
//                //then verify both the parsed hex contents and read back data match.
//
//                foreach(hexRange, hexData->ranges)
//
//                {
//
//                    if(deviceRange.start == hexRange.start)
//
//                    {
//
//                        //For this entire programmable memory address range, check to see if the data read from the device exactly
//
//                        //matches what was in the hex file.
//
//                        for(i = deviceRange.start; i < deviceRange.end; i++)
//
//                        {
//
//                            //For each byte of each device address (1 on PIC18, 2 on PIC24, since flash memory is 16-bit WORD array)
//
//                            for(j = 0; j < device->bytesPerAddressFLASH; j++)
//
//                            {
//
//                                //Check if the device response data matches the data we parsed from the original input .hex file.
//
//                                if(deviceRange.pDataBuffer[((i - deviceRange.start) * device->bytesPerAddressFLASH)+j] != hexRange.pDataBuffer[((i - deviceRange.start) * device->bytesPerAddressFLASH)+j])
//
//                                {
//
//                                    //A mismatch was detected.
//
//
//
//                                    //Check if this is a PIC24 device and we are looking at the "phantom byte"
//
//                                    //(upper byte [j = 1] of odd address [i%2 == 1] 16-bit flash words).  If the hex data doesn't match
//
//                                    //the device (which should be = 0x00 for these locations), this isn't a real verify
//
//                                    //failure, since value is a don't care anyway.  This could occur if the hex file imported
//
//                                    //doesn't contain all locations, and we "filled" the region with pure 0xFFFFFFFF, instead of 0x00FFFFFF
//
//                                    //when parsing the hex file.
//
//                                    if((device->family == Device::PIC24) && ((i % 2) == 1) && (j == 1))
//
//                                    {
//
//                                        //Not a real verify failure, phantom byte is unimplemented and is a don't care.
//
//                                    }
//
//                                    else
//
//                                    {
//
//                                        //If the data wasn't a match, and this wasn't a PIC24 phantom byte, then if we get
//
//                                        //here this means we found a true verify failure.
//
//                                        failureDetected = true;
//
//                                        if(device->family == Device::PIC24)
//
//                                        {
//
//                                            qWarning("Device: 0x%x Hex: 0x%x", *(unsigned short int*)&deviceRange.pDataBuffer[((i - deviceRange.start) * device->bytesPerAddressFLASH)+j], *(unsigned short int*)&hexRange.pDataBuffer[((i - deviceRange.start) * device->bytesPerAddressFLASH)+j]);
//
//                                        }
//
//                                        else
//
//                                        {
//
//                                            qWarning("Device: 0x%x Hex: 0x%x", deviceRange.pDataBuffer[((i - deviceRange.start) * device->bytesPerAddressFLASH)+j], hexRange.pDataBuffer[((i - deviceRange.start) * device->bytesPerAddressFLASH)+j]);
//
//                                        }
//
//                                        qWarning("Failed verify at address 0x%x", i);
//
//                                        IoWithDeviceCompleted("Verify", Comm::Fail, ((double)elapsed.elapsed()) / 1000);
//
//                                        return;
//
//                                    }
//
//                                }//if(deviceRange.pDataBuffer[((i - deviceRange.start) * device->bytesPerAddressFLASH)+j] != hexRange.pDataBuffer[((i - deviceRange.start) * device->bytesPerAddressFLASH)+j])
//
//                            }//for(j = 0; j < device->bytesPerAddressFLASH; j++)
//
//                        }//for(i = deviceRange.start; i < deviceRange.end; i++)
//
//                    }//if(deviceRange.start == hexRange.start)
//
//                }//foreach(hexRange, hexData->ranges)
//
//                //IoWithDeviceCompleted("Verify", Comm::Success, ((double)elapsed.elapsed()) / 1000);
//
//            }//if(writeFlash && (deviceRange.type == PROGRAM_MEMORY))
//
//            else if(writeEeprom && (deviceRange.type == EEPROM_MEMORY))
//
//            {
//
//                elapsed.start();
//
//                //IoWithDeviceStarted("Verifying Device's EEPROM Memory...");
//
//
//
//                result = comm->GetData(deviceRange.start,
//
//                        device->bytesPerPacket,
//
//                        device->bytesPerAddressEEPROM,
//
//                        device->bytesPerWordEEPROM,
//
//                        deviceRange.end,
//
//                        deviceRange.pDataBuffer);
//
//
//
//                if(result != Comm::Success)
//
//                {
//
//                    failureDetected = true;
//
//                    qWarning("Error reading device.");
//
//                    //IoWithDeviceCompleted("Verifying Device's EEPROM Memory", result, ((double)elapsed.elapsed()) / 1000);
//
//                }
//
//
//
//
//
//                //Search through all of the programmable memory regions from the parsed .hex file data.
//
//                //For each of the programmable memory regions found, if the region also overlaps a region
//
//                //that was included in the device programmed area (which just got read back with GetData()),
//
//                //then verify both the parsed hex contents and read back data match.
//
//                foreach(hexRange, hexData->ranges)
//
//                {
//
//                    if(deviceRange.start == hexRange.start)
//
//                    {
//
//                        //For this entire programmable memory address range, check to see if the data read from the device exactly
//
//                        //matches what was in the hex file.
//
//                        for(i = deviceRange.start; i < deviceRange.end; i++)
//
//                        {
//
//                            //For each byte of each device address (only 1 for EEPROM byte arrays, presumably 2 for EEPROM WORD arrays)
//
//                            for(j = 0; j < device->bytesPerAddressEEPROM; j++)
//
//                            {
//
//                                //Check if the device response data matches the data we parsed from the original input .hex file.
//
//                                if(deviceRange.pDataBuffer[((i - deviceRange.start) * device->bytesPerAddressEEPROM)+j] != hexRange.pDataBuffer[((i - deviceRange.start) * device->bytesPerAddressEEPROM)+j])
//
//                                {
//
//                                    //A mismatch was detected.
//
//                                    failureDetected = true;
//
//                                    qWarning("Device: 0x%x Hex: 0x%x", deviceRange.pDataBuffer[((i - deviceRange.start) * device->bytesPerAddressFLASH)+j], hexRange.pDataBuffer[((i - deviceRange.start) * device->bytesPerAddressFLASH)+j]);
//
//                                    qWarning("Failed verify at address 0x%x", i);
//
//                                    IoWithDeviceCompleted("Verify EEPROM Memory", Comm::Fail, ((double)elapsed.elapsed()) / 1000);
//
//                                    return;
//
//                                }
//
//                            }
//
//                        }
//
//                    }
//
//                }//foreach(hexRange, hexData->ranges)
//
//                //IoWithDeviceCompleted("Verifying", Comm::Success, ((double)elapsed.elapsed()) / 1000);
//
//            }//else if(writeEeprom && (deviceRange.type == EEPROM_MEMORY))
//
//            else if(writeConfig && (deviceRange.type == CONFIG_MEMORY))
//
//            {
//
//                elapsed.start();
//
//                //IoWithDeviceStarted("Verifying Device's Config Words...");
//
//
//
//                result = comm->GetData(deviceRange.start,
//
//                        device->bytesPerPacket,
//
//                        device->bytesPerAddressConfig,
//
//                        device->bytesPerWordConfig,
//
//                        deviceRange.end,
//
//                        deviceRange.pDataBuffer);
//
//
//
//                if(result != Comm::Success)
//
//                {
//
//                    failureDetected = true;
//
//                    qWarning("Error reading device.");
//
//                    //IoWithDeviceCompleted("Verifying Device's Config Words", result, ((double)elapsed.elapsed()) / 1000);
//
//                }
//
//
//
//                //Search through all of the programmable memory regions from the parsed .hex file data.
//
//                //For each of the programmable memory regions found, if the region also overlaps a region
//
//                //that was included in the device programmed area (which just got read back with GetData()),
//
//                //then verify both the parsed hex contents and read back data match.
//
//                foreach(hexRange, hexData->ranges)
//
//                {
//
//                    if(deviceRange.start == hexRange.start)
//
//                    {
//
//                        //For this entire programmable memory address range, check to see if the data read from the device exactly
//
//                        //matches what was in the hex file.
//
//                        for(i = deviceRange.start; i < deviceRange.end; i++)
//
//                        {
//
//                            //For each byte of each device address (1 on PIC18, 2 on PIC24, since flash memory is 16-bit WORD array)
//
//                            for(j = 0; j < device->bytesPerAddressConfig; j++)
//
//                            {
//
//                                //Compute an index into the device and hex data arrays, based on the current i and j values.
//
//                                arrayIndex = ((i - deviceRange.start) * device->bytesPerAddressConfig)+j;
//
//
//
//                                //Check if the device response data matches the data we parsed from the original input .hex file.
//
//                                if(deviceRange.pDataBuffer[arrayIndex] != hexRange.pDataBuffer[arrayIndex])
//
//                                {
//
//                                    //A mismatch was detected.  Perform additional checks to make sure it was a real/unexpected verify failure.
//
//
//
//                                    //Check if this is a PIC24 device and we are looking at the "phantom byte"
//
//                                    //(upper byte [j = 1] of odd address [i%2 == 1] 16-bit flash words).  If the hex data doesn't match
//
//                                    //the device (which should be = 0x00 for these locations), this isn't a real verify
//
//                                    //failure, since value is a don't care anyway.  This could occur if the hex file imported
//
//                                    //doesn't contain all locations, and we "filled" the region with pure 0xFFFFFFFF, instead of 0x00FFFFFF
//
//                                    //when parsing the hex file.
//
//                                    if((device->family == Device::PIC24) && ((i % 2) == 1) && (j == 1))
//
//                                    {
//
//                                        //Not a real verify failure, phantom byte is unimplemented and is a don't care.
//
//                                    }//Make further special checks for PIC18 non-J devices
//
//                                    else if((device->family == Device::PIC18) && (deviceRange.start == 0x300000) && ((i == 0x300004) || (i == 0x300007)))
//
//                                    {
//
//                                        //The "CONFIG3L" and "CONFIG4H" locations (0x300004 and 0x300007) on PIC18 non-J USB devices
//
//                                        //are unimplemented and should be masked out from the verify operation.
//
//                                    }
//
//                                    else
//
//                                    {
//
//                                        //If the data wasn't a match, and this wasn't a PIC24 phantom byte, then if we get
//
//                                        //here this means we found a true verify failure.
//
//                                        failureDetected = true;
//
//                                        if(device->family == Device::PIC24)
//
//                                        {
//
//                                            qWarning("Device: 0x%x Hex: 0x%x", *(unsigned short int*)&deviceRange.pDataBuffer[((i - deviceRange.start) * device->bytesPerAddressConfig)+j], *(unsigned short int*)&hexRange.pDataBuffer[((i - deviceRange.start) * device->bytesPerAddressConfig)+j]);
//
//                                        }
//
//                                        else
//
//                                        {
//
//                                            qWarning("Device: 0x%x Hex: 0x%x", deviceRange.pDataBuffer[((i - deviceRange.start) * device->bytesPerAddressConfig)+j], hexRange.pDataBuffer[((i - deviceRange.start) * device->bytesPerAddressConfig)+j]);
//
//                                        }
//
//                                        qWarning("Failed verify at address 0x%x", i);
//
//                                        IoWithDeviceCompleted("Verify Config Bit Memory", Comm::Fail, ((double)elapsed.elapsed()) / 1000);
//
//                                        return;
//
//                                    }
//
//                                }
//
//                            }
//
//                        }
//
//                    }
//
//                }//foreach(hexRange, hexData->ranges)
//
//                //IoWithDeviceCompleted("Verifying", Comm::Success, ((double)elapsed.elapsed()) / 1000);
//
//            }//else if(writeConfig && (deviceRange.type == CONFIG_MEMORY))
//
//            else
//
//            {
//
//                continue;
//
//            }
//
//        }//foreach(deviceRange, deviceData->ranges)
//
//
//
//        if(failureDetected == false)
//
//        {
//
//            //Successfully verified all regions without error.
//
//            //If this is a v1.01 or later device, we now need to issue the SIGN_FLASH
//
//            //command, and then re-verify the first erase page worth of flash memory
//
//            //(but with the exclusion of the signature WORD address from the verify,
//
//            //since the bootloader firmware will have changed it to the new/magic
//
//            //value (probably 0x600D, or "good" in leet speak).
//
//            if(deviceFirmwareIsAtLeast101 == true)
//
//            {
//
//                comm->SignFlash();
//
//                //Now re-verify the first erase page of flash memory.
//
//                if(device->family == Device::PIC18)
//
//                {
//
//                    startOfEraseBlock = extendedBootInfo.PIC18.signatureAddress - (extendedBootInfo.PIC18.signatureAddress % extendedBootInfo.PIC18.erasePageSize);
//
//                    result = comm->GetData(startOfEraseBlock,
//
//                            device->bytesPerPacket,
//
//                            device->bytesPerAddressFLASH,
//
//                            device->bytesPerWordFLASH,
//
//                            (startOfEraseBlock + extendedBootInfo.PIC18.erasePageSize),
//
//                            &flashData[0]);
//
//                    if(result != Comm::Success)
//
//                    {
//
//                        failureDetected = true;
//
//                        qWarning("Error reading, post signing, flash data block.");
//
//                    }
//
//
//
//                    //Search through all of the programmable memory regions from the parsed .hex file data.
//
//                    //For each of the programmable memory regions found, if the region also overlaps a region
//
//                    //that is part of the erase block, copy out bytes into the hexEraseBlockData[] buffer,
//
//                    //for re-verification.
//
//                    foreach(hexRange, hexData->ranges)
//
//                    {
//
//                        //Check if any portion of the range is within the erase block of interest in the device.
//
//                        if((hexRange.start <= startOfEraseBlock) && (hexRange.end > startOfEraseBlock))
//
//                        {
//
//                            unsigned int rangeSize = hexRange.end - hexRange.start;
//
//                            unsigned int address = hexRange.start;
//
//                            unsigned int k = 0;
//
//
//
//                            //Check every byte in the hex file range, to see if it is inside the erase block of interest
//
//                            for(i = 0; i < rangeSize; i++)
//
//                            {
//
//                                //Check if the current byte we are looking at is inside the erase block of interst
//
//                                if(((address+i) >= startOfEraseBlock) && ((address+i) < (startOfEraseBlock + extendedBootInfo.PIC18.erasePageSize)))
//
//                                {
//
//                                    //The byte is in the erase block of interst.  Copy it out into a new buffer.
//
//                                    hexEraseBlockData[k] = *(hexRange.pDataBuffer + i);
//
//                                    //Check if this is a signature byte.  If so, replace the value in the buffer
//
//                                    //with the post-signing expected signature value, since this is now the expected
//
//                                    //value from the device, rather than the value from the hex file...
//
//                                    if((address+i) == extendedBootInfo.PIC18.signatureAddress)
//
//                                    {
//
//                                        hexEraseBlockData[k] = (unsigned char)extendedBootInfo.PIC18.signatureValue;    //Write LSB of signature into buffer
//
//                                    }
//
//                                    if((address+i) == (extendedBootInfo.PIC18.signatureAddress + 1))
//
//                                    {
//
//                                        hexEraseBlockData[k] = (unsigned char)(extendedBootInfo.PIC18.signatureValue >> 8); //Write MSB into buffer
//
//                                    }
//
//                                    k++;
//
//                                }
//
//                                if((k >= extendedBootInfo.PIC18.erasePageSize) || (k >= sizeof(hexEraseBlockData)))
//
//                                    break;
//
//                            }
//
//                        }
//
//                    }//foreach(hexRange, hexData->ranges)
//
//
//
//                    //We now have both the hex data and the post signing flash erase block data
//
//                    //in two RAM buffers.  Compare them to each other to perform post-signing
//
//                    //verify.
//
//                    for(i = 0; i < extendedBootInfo.PIC18.erasePageSize; i++)
//
//                    {
//
//                        if(flashData[i] != hexEraseBlockData[i])
//
//                        {
//
//                            failureDetected = true;
//
//                            qWarning("Post signing verify failure.");
//
//                            EraseDevice();  //Send an erase command, to forcibly
//
//                            //remove the signature (which might be valid), since
//
//                            //there was a verify error and we can't trust the application
//
//                            //firmware image integrity.  This ensures the device jumps
//
//                            //back into bootloader mode always.
//
//                        }
//
//                    }
//
//                }
//
//
//
//            }//if(deviceFirmwareIsAtLeast101 == true)
//
//
//
//        }//if(failureDetected == false)
//
//
//
//        if(failureDetected == true)
//
//        {
//
//            log("Operation aborted due to error encountered during verify operation.");
//
//            log("Please try the erase/program/verify sequence again.");
//
//            log("If repeated failures are encountered, this may indicate the flash");
//
//            log("memory has worn out, that the device has been damaged, or that");
//
//            log("there is some other unidentified problem.");
//
//        }
//
//        else
//
//        {
//
//            IoWithDeviceCompleted("Verify", Comm::Success, ((double)elapsed.elapsed()) / 1000);
//
//            log("Erase/Program/Verify sequence completed successfully.");
//
//            log("You may now unplug or reset the device.");
//
//        }
//
//
//
//        emit setProgressBar(100);   //Set progress bar to 100%
//
//    }//void MainWindow::VerifyDevice()

    String GetObiVersionFromHex() {
        FillBootInfo();
        LoadHex();
        byte[] b = picDevice.deviceData.ranges.get(0).dataBuffer;
        long len = picDevice.deviceData.ranges.get(0).dataBufferLength;

        String s = "";
        for(int i=4096; i<len; i+=4) {
            byte b1 = b[i];
            byte b2 = b[i+1];

            int i1 = b1 & 0xff;
            int i2 = b2 & 0xff;

            if(b1 == 0) break;
            s += (char)b1;

            if(b2 == 0) break;
            s += (char)b2;

        }
        return s;
    }

    Bootloader(MyTestService myTestService) {
        this.myTestService = myTestService;
    }

}
