package com.obimon.obimon_mobile;

import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.util.Log;

import com.felhr.usbserial.CDCSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;

import org.achartengine.model.XYSeries;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.obimon.obimon_mobile.MyActivity.*;
import static com.obimon.obimon_mobile.ObimonDevice.ACTION_GATT_CONNECTED;

/**
 * Created by andrasveres on 22/03/15.
 */
public class MyTestService  extends Service {
    static final String BROADCAST_TIMECHANGED = "BROADCAST_TIMECHANGED";
    private final String TAG = "MyTestService";

    int i=0;

    DataSet dataSet = new DataSet();

    public ConcurrentHashMap<String, ObimonDevice> foundDevices = new ConcurrentHashMap<String, ObimonDevice>();

    boolean stop=false;

    boolean ntp = false;
    long ntpCorr =0;

    static String latestFirmwareDate = "wait";

    private Handler mHandler;
    BluetoothAdapter mBluetoothAdapter;
    BluetoothLeScanner mLEScanner;
    BluetoothManager mBluetoothManager;

    UsbDeviceConnection usbConnection;
    UsbManager usbManager;
    ManageObimon manageObimon;

    boolean resetAfterProgramming = false;
    boolean programmingInProgress = false;
    int programmingPercentage = 0;

    boolean otaInProgress = false;

    // BOOTLOADER HID
    UsbEndpoint usbEndpointRead = null;
    UsbEndpoint usbEndpointWrite = null;
    UsbDeviceConnection connectionWrite;
    UsbDeviceConnection connectionRead;
    Bootloader bootloader;

    TimeThread timeThread = null;

    ObimonDatabase obimonDatabase = null;

    /*
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            Log.d(TAG, "onReceive: Broadcast ----------------------------------------------=====================");


            // When discovery finds a device
            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                // Get the BluetoothDevice object from the Intent
                BluetoothDevice device = intent
                        .getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

                //you can get name by device.getName()
                Log.d(TAG, "XXXXXXXXXXXXXX onReceive: BroadcastReceived gets name "+device.getName());

            }
        }
    };
*/

    class StatusThread extends Thread {

        @Override
        public void run() {
            while(!stop) {
                try {
                    sleep(100);
                } catch (InterruptedException e) {
                    Log.e(TAG, e.getMessage());
                    e.printStackTrace();
                }

                for(ObimonDevice obi : foundDevices.values()) {
                    while(true) {
                        XYSeries s = obi.series;
                        //Log.d(TAG, "AddData: "+series.getItemCount());
                        if(s.getItemCount()==0) break;
                        if (s.getX(0) < System.currentTimeMillis() - 1000 * graphHistory) s.remove(0);
                        else break;
                    }

                    while(true) {
                        XYSeries s = obi.seriesAcc;
                        //Log.d(TAG, "AddData: "+series.getItemCount());
                        if(s.getItemCount()==0) break;
                        if (s.getX(0) < System.currentTimeMillis() - 1000 * graphHistory) s.remove(0);
                        else break;
                    }

                }

            }
        }
    }


    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        myTestService = this;

        //ReceiveThread receiveThread = new ReceiveThread();
        //receiveThread.start();

        StatusThread statusThread = new StatusThread();
        statusThread.start();

        if(timeThread==null) {
            timeThread = new TimeThread();
            timeThread.start();
        }

        //BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        //if (mBluetoothAdapter == null) {
            // Device does not support Bluetooth
        //    Log.i("XXXX", "Bluetooth not supported");
        //}

        //mHandler = new Handler();

        IntentFilter notificationFilter = new IntentFilter(STOPACTION);
        registerReceiver(broadcastReceiver, notificationFilter);

        // Initializes Bluetooth adapter.
        mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = mBluetoothManager.getAdapter();

        IntentFilter bleFilter = new IntentFilter(ACTION_GATT_CONNECTED);
        //bleFilter.addAction(ACTION_GATT_CONNECTED);
        registerReceiver(mBleReceiver, bleFilter);

        scanLeDevice(true);

        // USB
        manageObimon = new ManageObimon(this);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        //mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        //filter.addAction("android.hardware.usb.action.USB_DEVICE_ATTACHED");
        //filter.addAction("android.hardware.usb.action.USB_DEVICE_DETACHED");
        registerReceiver(mUsbReceiver, filter);
        Log.d("USB", "RegisterReceiver");


        checkUSBDevices();

        // Test HEX file
        Bootloader bl = new Bootloader(this);
        latestFirmwareDate = bl.GetObiVersionFromHex();

        Log.d("BL", "D '"+latestFirmwareDate+"'");

        // DATABASE
        obimonDatabase = ObimonDatabase.getInstance(this);
        List<SyncData> syncDataList = obimonDatabase.syncDataDao().getSyncDataList();

        Log.d("OBIDB", "syncDataList size "+syncDataList.size());



    }

    void checkUSBDevices() {
        // This snippet will open the first usb device connected, excluding usb root hubs
        UsbDevice device;
        HashMap<String, UsbDevice> usbDevices = usbManager.getDeviceList();

        Log.d("USB", "checkUSBDevices");

        if(usbDevices.isEmpty()) return;

        for(Map.Entry<String, UsbDevice> entry : usbDevices.entrySet())
        {
            device = entry.getValue();
            int deviceVID = device.getVendorId();
            int devicePID = device.getProductId();

            Log.i("USB", "VID "+deviceVID+" PID "+devicePID);

            //if(deviceVID != 0x1d6b || (devicePID != 0x0001 || devicePID != 0x0002 || devicePID != 0x0003))
            if(deviceVID == 1240 && (devicePID == 10 || (devicePID == 60 && resetAfterProgramming == false)))
            {
                Log.d("USB", "Request permission");
                // We are supposing here there is only one device connected and it is our serial device
                boolean hasPermission = usbManager.hasPermission(device);
                Log.d("USB", "hasPermissionAlready "+hasPermission);
                PendingIntent mPendingIntent = PendingIntent.getBroadcast(this, 0, new Intent("com.obimon.obimon_mobile.USB_PERMISSION"), 0);
                usbManager.requestPermission(device, mPendingIntent);

            }

            resetAfterProgramming = false;

            //if(deviceVID == 1240 && devicePID == 60) {
            //    Log.d("USB", "BOOTLOADER");

            //}

        }
    }

    // http://torvafirmus-android.blogspot.hu/2011/09/implementing-usb-hid-interface-in.html


    boolean connectBL(UsbDevice device) {
        UsbInterface usbInterfaceRead = null;
        UsbInterface usbInterfaceWrite = null;
        UsbEndpoint ep1 = null;
        UsbEndpoint ep2 = null;

        boolean UsingSingleInterface = true;

        //Log.d("BL", "UsingSingleInterface "+UsingSingleInterface);

        if (UsingSingleInterface)
        {
            // Using the same interface for reading and writing
            usbInterfaceRead = device.getInterface(0x00);
            usbInterfaceWrite = usbInterfaceRead;

            //Log.d("BL", "InterfaceRead EndpointCount "+ usbInterfaceRead.getEndpointCount());

            if (usbInterfaceRead.getEndpointCount() == 2)
            {
                ep1 = usbInterfaceRead.getEndpoint(0);
                ep2 = usbInterfaceRead.getEndpoint(1);
            }
        }
        else        // if (!UsingSingleInterface)
        {
            usbInterfaceRead = device.getInterface(0x00);
            usbInterfaceWrite = device.getInterface(0x01);
            if ((usbInterfaceRead.getEndpointCount() == 1) && (usbInterfaceWrite.getEndpointCount() == 1))
            {
                ep1 = usbInterfaceRead.getEndpoint(0);
                ep2 = usbInterfaceWrite.getEndpoint(0);
            }
        }


        if ((ep1 == null) || (ep2 == null))
        {
            Log.d("BL", "ERROR Null endpoints");
            return false;
        }

// Determine which endpoint is the read, and which is the write

        if (ep1.getType() == UsbConstants.USB_ENDPOINT_XFER_INT)
        {
            if (ep1.getDirection() == UsbConstants.USB_DIR_IN)
            {
                usbEndpointRead = ep1;
            }
            else if (ep1.getDirection() == UsbConstants.USB_DIR_OUT)
            {
                usbEndpointWrite = ep1;
            }
        }
        if (ep2.getType() == UsbConstants.USB_ENDPOINT_XFER_INT)
        {
            if (ep2.getDirection() == UsbConstants.USB_DIR_IN)
            {
                usbEndpointRead = ep2;
            }
            else if (ep2.getDirection() == UsbConstants.USB_DIR_OUT)
            {
                usbEndpointWrite = ep2;
            }
        }
        if ((usbEndpointRead == null) || (usbEndpointWrite == null))
        {
            return false;
        }
        connectionRead = usbManager.openDevice(device);
        connectionRead.claimInterface(usbInterfaceRead, true);


        if (UsingSingleInterface)
        {
            connectionWrite = connectionRead;
        }
        else // if (!UsingSingleInterface)
        {
            connectionWrite = usbManager.openDevice(device);
            connectionWrite.claimInterface(usbInterfaceWrite, true);
        }

        bootloader = new Bootloader(this);
        new Thread(bootloader).start();

        return true;
    }

    ByteBuffer blRead() {
        // READ==================
        int bufferDataLength = usbEndpointRead.getMaxPacketSize();
        ByteBuffer buffer = ByteBuffer.allocate(bufferDataLength + 0);
        UsbRequest requestQueued = null;
        UsbRequest request = new UsbRequest();
        request.initialize(connectionRead, usbEndpointRead);

        request.queue(buffer, bufferDataLength);
        requestQueued = connectionRead.requestWait();
        if (!request.equals(requestQueued)) {

            Log.d("BL", "WHAT?");
            buffer = null;
        } else buffer.order(ByteOrder.LITTLE_ENDIAN);


        request.cancel();
        request.close();


        return buffer;
    }

    int blWrite(ByteBuffer buffer) {
        buffer.rewind();

        // WRITE ================
        int bufferDataLength = usbEndpointWrite.getMaxPacketSize();

        Log.d("BL", "MaxPacketSize "+bufferDataLength+" bufferDataLength "+bufferDataLength);

        UsbRequest request = new UsbRequest();

        request.initialize(connectionWrite, usbEndpointWrite);
        request.queue(buffer, bufferDataLength);

        try
        {
            UsbRequest resp = connectionWrite.requestWait();
            if(request.equals(resp)) return 0;
            if(resp == null) {
                Log.d("BL", "blWrite error null");
                return -1;
            }
            else {
                Log.d("BL", "blWrite error other");
                return -1;
            }
        }
        catch (Exception ex)
        {
            // An exception has occured
            Log.d("BL", ex.getMessage());
            return -1;
        }
    }

    void connectUSB(UsbDevice device) {
        Log.d("USB", "connectUSB");

        usbConnection = usbManager.openDevice(device);
        //serialPort = UsbSerialDevice.createUsbSerialDevice(device, connection);
        manageObimon.serialPort = new CDCSerialDevice(device, usbConnection);

        Log.d("USB", "serialPort "+manageObimon.serialPort);

        if(manageObimon.serialPort.open()) {

            manageObimon.serialPort.setBaudRate(9600);
            manageObimon.serialPort.setDataBits(UsbSerialInterface.DATA_BITS_8);
            manageObimon.serialPort.setStopBits(UsbSerialInterface.STOP_BITS_1);
            manageObimon.serialPort.setParity(UsbSerialInterface.PARITY_NONE);
            manageObimon.serialPort.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF);

            manageObimon.serialPort.read(manageObimon.mCallback);
            //serialPort.getCTS(ctsCallback);
            //serialPort.getDSR(dsrCallback);
            manageObimon.state = ManageObimon.ManageState.IDLE;

            manageObimon.startThread();

        } else {
            manageObimon.state = ManageObimon.ManageState.UNCONNECTED;
            Log.d("USB", "Open error");
        }

        manageObimon.name = null;
        manageObimon.apiversion = -1;

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        //    return super.onStartCommand(intent, flags, startId);

        if(intent!=null) {

            Log.i(TAG, "intent ");

            // Extract the receiver passed into the service

            String dataString = intent.getDataString();
            Log.d(TAG, "intent " + dataString);

            String val = intent.getStringExtra("foo");
            Log.d(TAG, "intent val " + val);
        }


        return Service.START_STICKY;

    }

    private ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            //Log.d(TAG, "onScanResult " + result);

            parseScanData(result);

            //super.onScanResult(callbackType, result);

        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.d(TAG, "onScanFailed " + errorCode);

            //super.onScanFailed(errorCode);
        }
    };

    void scanLeDevice(final boolean enable) {
        long SCAN_PERIOD = 10000;

        Log.d(TAG, "scanLeDevice " + enable);

        if (enable) {

            /*
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mBluetoothAdapter.stopLeScan(mLeScanCallback);
                }
            }, SCAN_PERIOD);
*/

            ScanFilter filter = new ScanFilter.Builder().build();
            List<ScanFilter> filters = new ArrayList<ScanFilter>();
            filters.add(filter);

            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();

            //mBluetoothAdapter.getBluetoothLeScanner().startScan(mScanCallback);
            mBluetoothAdapter.getBluetoothLeScanner().startScan(filters, settings, mScanCallback);
            //mBluetoothAdapter.startLeScan(mLeScanCallback);

        } else {
            mBluetoothAdapter.getBluetoothLeScanner().stopScan(mScanCallback);
            //mBluetoothAdapter.stopLeScan(mLeScanCallback);
        }
    }



    public void parseScanData(ScanResult scanResult) {

        String TAG = "BtData";

        // if not Microchip
        String addr = scanResult.getDevice().getAddress();
        if(!addr.startsWith("00:1E:C0"))
            if(!addr.startsWith("00:06:66"))
                if(!addr.startsWith("04:91:62")) {

                    //Log.d("BLEMANU","New vendor "+addr);
                    return;
                }

        ScanRecord scanRecord = scanResult.getScanRecord();
        long rxTime = System.currentTimeMillis() -
                SystemClock.elapsedRealtime() +
                scanResult.getTimestampNanos() / 1000000;

        BleAdvertisedData badata = BleUtil.parseAdertisedData(scanRecord.getBytes());

        //if(device.getAddress().compareTo("00:1E:C0:30:FE:D1")==0) Log.d(TAG,"HHH "+device.getAddress());

        // if does not have manufacturer specific data inside
        if(badata.manufacturerSpecificBytes == null) return;

        ObimonDevice obi=null;
        if(!foundDevices.containsKey(addr)) {
            Log.d(TAG,"New device "+addr);
            obi = new ObimonDevice(MyTestService.this, scanResult.getDevice());
            //obi.connected();
            foundDevices.put(addr, obi);

            obi.color = ObiColors.colors[foundDevices.size() % ObiColors.colors.length];

        } else {
            obi = foundDevices.get(addr);
        }

        if(obi.connectionState == ObimonDevice.ConnectionState.IDLE) {
            if(obi.selected){
            // if(addr.endsWith("D0")) {
            //    if(addr.endsWith("FE:DD")) {

                    //if(addr.endsWith("FE:C3")) {

                if(scanResult.isConnectable() == true) {
                    Log.d("BBB", "CONNECTABLE "+addr);
                } else {
                    Log.d("BBB", "NOT CONNECTABLE "+addr);
                }

                Log.d("BBB", "Start connect "+addr);

                obi.connectionState = ObimonDevice.ConnectionState.CONNECTING;

                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {}

                BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(addr);

                if(device == null) {
                    Log.d("BBB", "Device not available "+addr);
                } else {
                    obi.mBluetoothGatt = device.connectGatt(this, true, obi.mGattCallback);
                }
            }
        }


        if(obi.selected == false && obi.connectionState == ObimonDevice.ConnectionState.CONNECTED) {
            Log.d("BBB", "Disconnect "+addr);
            if(obi.mBluetoothGatt!=null) obi.mBluetoothGatt.disconnect();
        }


        obi.signal = scanResult.getRssi();
        obi.lastSeen = System.currentTimeMillis();

        int len = badata.manufacturerSpecificBytes.length;

        if(len<3) {
            Log.e(TAG, "Wrong bt msg len");
            return;
        }

        int msgType = badata.manufacturerSpecificBytes[0];

        int n = 0;
        for (int i = 1; i < 3; i++) {
            n *= 256;
            n += badata.manufacturerSpecificBytes[i] & 0x000000ff;
        }

        if(n == obi.lastId) {
            Log.d(TAG, ""+obi.addr+" Already received "+obi.name+" "+n+" msgType "+msgType);
            return;
        }

        obi.CalcLoss(n);

        switch(msgType) {

            // TS broadcast
            case 0x55: {
                obi.lastTsBroadcast = System.currentTimeMillis();



                if(badata.manufacturerSpecificBytes.length!=11) {
                    Log.e(TAG, "WRONG TS SIZE "+obi.addr+" "+obi.name+" "+badata.manufacturerSpecificBytes.length);
                    break;
                }

                long ts = 0;
                for (int i=3; i < 8+3; i++) {
                    int b = badata.manufacturerSpecificBytes[i] & 0x000000ff;
                    ts *= 256;
                    ts += b;
                }
                ts *= 1000;

                Log.d(TAG, "" + obi.addr + " TS BROADCAST "+ ts+ " ====================== ");

                break;
            }

            // Session
            case 0x60: {

                if(badata.manufacturerSpecificBytes.length!=15) {
                    Log.e(TAG, "WRONG SESSION SIZE "+obi.addr+" "+obi.name+" "+badata.manufacturerSpecificBytes.length);
                    break;
                }


                long session=ParseSession(badata.manufacturerSpecificBytes, 3);
                /*for (int i=3; i < 4+3; i++) {
                    int b = badata.manufacturerSpecificBytes[i] & 0x000000ff;
                    session *= 256;
                    session += b;
                }*/
                obi.session = session;

                long ts = ParseTick(badata.manufacturerSpecificBytes,7);

                long offset = rxTime - ts;

                Log.d(TAG, "" + obi.addr + " "+obi.name+" SESSION ts="+ ts+ " session="+session+" ====================== offset " +offset+" ntpcorr "+ntpCorr);

                //     void SaveSessionData(ObimonDevice obi, long t, long session, long device_t, long offset) {

                SaveSessionData(obi, rxTime, session, ts, offset);

                //SyncData d = new SyncData(rxTime, addr, session, ts, offset, ntpCorr);
                //obimonDatabase.syncDataDao().insertSyncData(d);
                // obi.lastSessionSync = rxTime;

                break;
            }

            // compact
            case 0x14: {

                //for(int j = 0; j<len; j++) Log.d(TAG, ""+obi.device.getAddress()+" MMM "+(Integer.toHexString(badata.manufacturerSpecificBytes[j] & 0x000000ff)));

                int i = 3;
                //     send("N,14%04x%02x%02x%02x%04x%s", nsent, apiversion, b, m, s, hexname);

                int apiversion = badata.manufacturerSpecificBytes[i++] & 0x000000ff;
                int bat = badata.manufacturerSpecificBytes[i++] & 0x000000ff;
                int mem = badata.manufacturerSpecificBytes[i++] & 0x000000ff;

                int syn1 = badata.manufacturerSpecificBytes[i++] & 0x000000ff;
                int syn2 = badata.manufacturerSpecificBytes[i++] & 0x000000ff;
                int sync = (syn2 + syn1 * 256);

                obi.bat = bat / 10.0;
                obi.mem = mem;

                long now = (rxTime + ntpCorr);
                long now_device = (long) (now * 32.768) / 1024;
                long trimmed = now_device & 0x0000ffff;
                Log.d(TAG, "" + addr + " " + now_device + " " + trimmed + " " + sync);

                //double diff = (trimmed - sync) * 1024.0 / 32.768;

                //obi.sync = diff;

                String s = ParseName(badata.manufacturerSpecificBytes, len-i, i);
                obi.name = s;

                /*
                String s = "";

                for (; i < len; i++) {
                    int b = badata.manufacturerSpecificBytes[i] & 0x000000ff;
                    char c = (char) (b & 0xFF);
                    if (c == 0) break;
                    s += c;
                }
                obi.name = s;
*/

                obi.series.setTitle(obi.name);
                obi.seriesAcc.setTitle(obi.name+"_acc");

                obi.apiversion = apiversion;

                Log.d(TAG, "" + obi.addr + " COMPACT====================== " + n + " api:" + apiversion + " name:" + obi.name + " bat:" + bat + " mem:" + mem + " sync:" + sync);

                //obi.AddData(n, gsr);
                break;

            }

            // Uptime
            case 0x15: {

                //for(int j = 0; j<len; j++) Log.d(TAG, ""+obi.device.getAddress()+" MMM "+(Integer.toHexString(badata.manufacturerSpecificBytes[j] & 0x000000ff)));

                int i = 3;
                //     send("N,14%04x%02x%02x%02x%04x%s", nsent, apiversion, b, m, s, hexname);
                int uptime = 0;
                for (int j = 0; j < 4; j++) {
                    uptime *= 256;
                    uptime += badata.manufacturerSpecificBytes[i++] & 0x000000ff;
                }

                int uptime_meas = 0;
                for (int j = 0; j < 4; j++) {
                    uptime_meas *= 256;
                    uptime_meas += badata.manufacturerSpecificBytes[i++] & 0x000000ff;
                }

                int lastbat = badata.manufacturerSpecificBytes[i++] & 0x000000ff;
                int bat = badata.manufacturerSpecificBytes[i++] & 0x000000ff;

                obi.bat = bat / 10.0;

                Log.d(TAG, "" + obi.addr + " UPTIME====================== " + n + " name:" + obi.name + " lastbat:" + lastbat + " bat:" + bat + " uptime:" + uptime + " uptime_meas:" + uptime_meas);

                //obi.AddData(n, gsr);
                break;

            }

            // name
            case 0x12: {

                //int apiversion = badata.manufacturerSpecificBytes[3] & 0x000000ff;

                String s = "";

                int i = 3;
                for (; i < len; i++) {
                    int b = badata.manufacturerSpecificBytes[i] & 0x000000ff;
                    char c = (char) (b & 0xFF);
                    if (c == 0) break;
                    s += c;
                }
                obi.name = s;

                obi.series.setTitle(obi.name);
                obi.seriesAcc.setTitle(obi.name+"_acc");

                //obi.apiversion = apiversion;

                Log.d(TAG, "" + obi.addr + " OBSOLETE NAME======================= " + n + " name:" + obi.name);

                //obi.AddData(n, gsr);
                break;

            }

            // build
            case 0x13: {

                String s = "";

                for (int i = 3; i < len; i++) {
                    int b = badata.manufacturerSpecificBytes[i] & 0x000000ff;
                    char c = (char) (b & 0xFF);
                    s += c;
                }

                obi.build = s;
                //obi.series.setTitle(s);

                Log.d(TAG, "" + obi.addr + " BUILD======================= " + n + " name:" + obi.name + " build:" + s);

                //obi.AddData(n, gsr);
                break;

            }

            // GSR data
            case 0x22: {

                int acc = ParseAcc(badata.manufacturerSpecificBytes, 3);
                int gsr = ParseGsr(badata.manufacturerSpecificBytes, 4);

                //int gsr = 0;
                //int acc= badata.manufacturerSpecificBytes[3] & 0x000000ff;
                //for (int i = 4; i < 7; i++) {
                //    gsr *= 256;
                //    gsr += badata.manufacturerSpecificBytes[i] & 0x000000ff;
                //}

                // only add adv data is there is no data from connection
                if(System.currentTimeMillis() - obi.lastCharacteristics > 1000) {
                    Log.d(TAG, "GSRDATA==== "+addr+" "+n+" "+gsr+" acc "+acc +" d_rxTime:"+(rxTime-obi.lastGsrTime));

                    obi.lastGsrTime = System.currentTimeMillis();
                    obi.AddData(rxTime, gsr, acc);
                }

                break;

            }

            // memory and time
            case 0x11: {
                obi.bat = badata.manufacturerSpecificBytes[3] / 10.0;

                int memptr = 0;
                for (int i = 4; i < 8; i++) {
                    memptr *= 256;
                    memptr += badata.manufacturerSpecificBytes[i] & 0x000000ff;
                }

                int blocks = memptr / 256;
                int total_blocks = 8 * 1024 * 1024 / 256;
                int free_blocks = total_blocks - blocks;
                int sec_per_block = (256 - 8) / 4 / 8;
                int free_sec = free_blocks * sec_per_block;

                obi.mem = free_sec;

                long tick = 0;
                for (int i = 8; i < 16; i++) {
                    tick *= 256;
                    tick += badata.manufacturerSpecificBytes[i] & 0x000000ff;
                }

                long ts = (long) (tick / 32.768);

                long now = System.currentTimeMillis() + ntpCorr;
                //long d = ts - now;
                //obi.sync = d;

                Log.d(TAG, "" + obi.addr + " STAT " + obi.name + " ===== " + n + " " + obi.bat + " " + memptr + " " + tick + " " + ntpCorr);

                //obi.AddData(n, gsr);

                break;

            }

            default: {
                Log.d(TAG, "UNKNOWN MSG "+obi.addr+" "+obi.name);
            }
        }
    }

    void SaveSessionData(ObimonDevice obi, long t, long session, long device_t, long offset) {
        SyncData d = new SyncData(t, obi.addr, session, device_t, offset, ntpCorr);
        obimonDatabase.syncDataDao().insertSyncData(d);

        obi.lastSessionSync = t;
    }

    String ParseName(byte[] buf, int len, int offset) {
        String s = "";

        for (i=0; i < len; i++) {
            int b = buf[i+offset] & 0x000000ff;
            char c = (char) (b & 0xFF);
            if (c == 0) break;
            s += c;
        }
        return s;
    }

    long ParseSession(byte[] buf, int offset) {
        long session=0;
        for (int i=0; i < 4; i++) {
            int b = buf[i+offset] & 0x000000ff;
            session *= 256;
            session += b;
        }
        return session;
    }

    long ParseTick(byte[] buf, int offset) {
        long ts = 0;
        for (int i=0; i < 8; i++) {
            int b = buf[i+offset] & 0x000000ff;
            ts *= 256;
            ts += b;
        }
        ts /= 32.768;

        return ts;
    }

    int ParseAcc(byte[] buf, int offset) {
        int acc= buf[offset] & 0x000000ff;
        return acc;
    }

    int ParseGsr(byte[] buf, int offset) {
        int gsr = 0;

        for (int i = 0; i < 3; i++) {
            gsr *= 256;
            gsr += buf[i+offset] & 0x000000ff;
        }

        return gsr;
    }

/*    private BluetoothAdapter.LeScanCallback mLeScanCallback =
            new BluetoothAdapter.LeScanCallback() {
                @Override
                public void onLeScan(final BluetoothDevice device, int rssi,
                                     byte[] scanRecord) {

                    parseScanData(device, rssi, scanRecord);
                }
            };*/



    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d("BRC","Received");
        }
    };



    private final BroadcastReceiver mBleReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            Log.d("BBB", "mBleReceiver received: "+action);

            if(ACTION_GATT_CONNECTED.equals(action)) {
                Log.d("BBB", "ACTION_GATT_CONNECTED");
            }
        }
    };

    private static final String ACTION_USB_PERMISSION =  "com.obimon.obimon_mobile.USB_PERMISSION";
    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();



            Log.d("USB", "Action: "+action);

            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if(device != null){
                            //call method to set up device communication
                            Log.d("USB", "permission granted " + device);

                            if(device.getProductId() == 60) {
                                Log.d("BL", "Bootload mode connected");
                                connectBL(device);
                            }

                            if(device.getProductId() == 10) {
                                Log.d("USB", "CDC Connect");
                                connectUSB(device);
                            }


                        }
                    }
                    else {
                        Log.d("USB", "permission denied for device " + device);
                        if(programmingInProgress) programmingInProgress = false;
                    }
                }
            }

            if (action.equals("android.hardware.usb.action.USB_DEVICE_ATTACHED")) {
                checkUSBDevices();
            }

            if (action.equals("android.hardware.usb.action.USB_DEVICE_DETACHED")) {

                Log.d("USB", "Close serial port");
                if(manageObimon.serialPort != null) manageObimon.serialPort.close();

                manageObimon.state = ManageObimon.ManageState.UNCONNECTED;

                manageObimon.name=null;
                manageObimon.apiversion=-1;
                manageObimon.build=null;
                manageObimon.btversion=0;
                manageObimon.mac=null;

                manageObimon.stopThread();
            }
        }
    };

    @Override
    public void onDestroy() {
        super.onDestroy();

        Log.i(TAG, "Service destroy");

        stop = true;

        unregisterReceiver(mUsbReceiver);
        unregisterReceiver(mBleReceiver);
        unregisterReceiver(broadcastReceiver);

        scanLeDevice(false);

        if(timeThread!=null) {
            timeThread.stop = true;
            timeThread = null;
        }

        //unregisterReceiver(mReceiver);

        for(ObimonDevice o : foundDevices.values()) {
            o.stopObimonDevice();
        }

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        stopSelf();


    }

    class TimeThread extends Thread {

        boolean stop = false;
        long timeDiff=-1;

        long lastScanStart=System.currentTimeMillis();

        public TimeThread() {
        }

        @Override
        public void run() {

            long lastNtpUpdate = 0;


            while(!stop) {

                if(System.currentTimeMillis() - lastScanStart > 60000) {
                    scanLeDevice(true);
                    lastScanStart = System.currentTimeMillis();
                    Log.d("BBB", "RESTART SCAN every 1 minutes");
                }

                if(System.currentTimeMillis() - lastNtpUpdate >= 300000) {

                    SntpClient client = new SntpClient();
                    if (client.requestTime("time1.google.com", 1000)) {
                        long now = client.getNtpTime() + SystemClock.elapsedRealtime() - client.getNtpTimeReference();

                        ntp = true;
                        ntpCorr = client.clockOffset;

                        timeDiff = now - System.currentTimeMillis();

                        Log.i(TAG, "NTP RESPONSE " + now + " Timediff " + timeDiff + " rtt " + client.getRoundTripTime());

                    } else {

                        ntpCorr = 0; // -1000000000;
                        ntp = false;
                    }

                    lastNtpUpdate = System.currentTimeMillis();
                }

                //Log.d(TAG, "Send BROADCAST_TIMECHANGED");
                LocalBroadcastManager.getInstance(myTestService).sendBroadcast(new Intent(BROADCAST_TIMECHANGED));

                try {
                    sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

            }
        }


    }


}
