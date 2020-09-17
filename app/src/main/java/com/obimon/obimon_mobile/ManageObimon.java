package com.obimon.obimon_mobile;

import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import androidx.core.content.FileProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;

import com.felhr.usbserial.CDCSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.obimon.obimon_mobile.ManageObimon.ManageState.CHANGE_NAME;
import static com.obimon.obimon_mobile.ManageObimon.ManageState.ERASE;
import static com.obimon.obimon_mobile.ManageObimon.ManageState.IDLE;
import static com.obimon.obimon_mobile.ManageObimon.ManageState.RESET;
import static com.obimon.obimon_mobile.ManageObimon.ManageState.READMEM;
import static com.obimon.obimon_mobile.ManageObimon.ManageState.UNCONNECTED;

import static java.lang.Math.abs;

/**
 * Created by av on 2016.11.06..
 */

public class ManageObimon {
    public static final String BROADCAST_REFRESH = "BROADCAST_REFRESH";
    //MyActivity myActivity = null;

    //Context context=null;

    String name = null;

    // for post-sync
    long offset=0;
    long dumpSession=0;
    long lastBlockTs=0;
    long lastTs=0;
    int nSession=0;

    private Handler mHandler = new Handler();

    Intent emailIntent=null;
    boolean sendEmail=false;

    MyTestService myTestService = null;

    //byte bret[] = null;

    long memptr = 0;
    //long sync = 0;

    int apiversion=-1;
    String build=null;
    String mac;
    int btversion=0;

    int progress = 0;

    UpdateThread manageUpdateThread = null;

    CDCSerialDevice serialPort = null;

    class DataBlock {
        byte b[] = null;

        DataBlock(byte b[]) {
            this.b = b;
        }
    }

    BlockingQueue<DataBlock> queue = new ArrayBlockingQueue<DataBlock>(131072);

    public enum ManageState {
        UNCONNECTED, READMEM, ERASE, IDLE, CHANGE_NAME, CHANGE_GROUP, RESET
    }

    ManageState state = UNCONNECTED;

    public UsbSerialInterface.UsbReadCallback mCallback = new UsbSerialInterface.UsbReadCallback() {
        @Override
        public void onReceivedData(byte[] arg0) {
            rec(arg0);
        }
    };

    /*
     * State changes in the CTS line will be received here
     */
    public UsbSerialInterface.UsbCTSCallback ctsCallback = new UsbSerialInterface.UsbCTSCallback() {
        @Override
        public void onCTSChanged(boolean state) {
            Log.d("USB", "CTS Callback");
        }
    };

    /*
     * State changes in the DSR line will be received here
     */
    public UsbSerialInterface.UsbDSRCallback dsrCallback = new UsbSerialInterface.UsbDSRCallback() {
        @Override
        public void onDSRChanged(boolean state) {
            Log.d("USB", "DSR Callback");
        }
    };

    synchronized void rec(byte b[]) {
        if (b == null) return;

        if (Arrays.equals(Arrays.copyOfRange(b, 0, 6), "Obimon".getBytes())) {
            //Log.d("DUMP", "Obimon got on USB");
            return;
        }

        DataBlock data = new DataBlock(b.clone());
        queue.offer(data);

    }



    ManageObimon(MyTestService myTestService) {
        this.myTestService = myTestService;
    }

    String sendCmd(String cmd) {
        Log.d("USB", "sendCmd: "+cmd);

        if (serialPort == null) return null;
        if (state == UNCONNECTED) return null;

        queue.clear();

        serialPort.write(cmd.getBytes());
        long start = System.currentTimeMillis();

        DataBlock db=null;
        try {
            db = queue.poll(1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
            return null;
        }

        if (db == null) {
            Log.d("USB", "No response");
            return null;
        }

        if (db.b == null) {
            Log.d("USB", "Empty response");
            return null;
        }

        if (db.b.length == 0) {
            Log.d("USB", "Zero length response");
            return null;
        }

        if (db.b[0] != cmd.charAt(0)) {
            Log.d("USB", "SendCMD wrong response "+((char)db.b[0]));
            return null;
        }

        String ret = null;
        String s=null;
        try {
            s = new String(db.b, "UTF-8");
            ret = s.substring(2);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            return null;
        }

        Log.d("USB", "sendCmd ret="+s);

        return ret;
    }

    void sendBinaryCmd(String cmd) {
        queue.clear();
        serialPort.write(cmd.getBytes());
        //long start = System.currentTimeMillis();
    }

    int getMemPtr() {
        String ret = sendCmd("m");

        if (ret == null) return -1;

        memptr = Long.parseLong(ret);
        return 0;
    }

    public class UpdateThread extends Thread {
        boolean stop = false;

        public void run() {

            Log.d("USB", "Start ManageObimon UpdateThread");

            while (!stop) {
                if(myTestService.stop) break;

                try {
                    sleep(200);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                LocalBroadcastManager.getInstance(myTestService).sendBroadcast(new Intent(BROADCAST_REFRESH));

                Log.d("USB", "State "+state);

                if (serialPort == null) {
                    state = UNCONNECTED;
                    break;
                }

                if(state == UNCONNECTED) continue;

                if (state == RESET) {
                    Log.d("BL", "Send reset to device in CDC mode");
                    serialPort.write("r".getBytes());
                    continue;
                }

                if (state == READMEM) {
                    //readMem();
                    readMem();
                    state = IDLE;
                }

                if (state == ERASE) {
                    eraseMem();
                    state = IDLE;
                }

                if (state == CHANGE_NAME) {
                    Log.d("USB", "Change name: " + name);
                    sendCmd("n " + name);
                    state = IDLE;
                }


                if(state == UNCONNECTED) continue;
                //getSync();

                //if(abs(sync)>30) {
                //    setSync();
                //}

                getMemPtr();

                String ret;

                if (name == null) {
                    ret = sendCmd("n");
                    name = ret;
                    Log.d("USB", "NAME " + ret);
                }

                if(apiversion==-1) {
                    ret = sendCmd("a");
                    if (ret == null) apiversion = -1;
                    else apiversion = Integer.parseInt(ret);
                    Log.d("USB", "APIVERSION " + ret);
                }

                if(build == null) {
                    build = sendCmd("c");
                    if (build == null) Log.d("USB", "BUILD UNKNOWN");
                    else Log.d("USB", "BUILD " + build);
                }


                if(mac == null) {
                    String macstr = sendCmd("M");
                    if (macstr == null) {
                        Log.d("USB", "MAC UNKNOWN");
                        mac = "";
                    } else {

                        mac=macstr.substring(0,2);
                        for(int i=2; i<12; i+=2) {
                            mac+=":";
                            mac+=macstr.substring(i,i+2);
                        }

                        Log.d("USB", "MAC " + macstr+"->"+mac);

                    }

                }

                if(btversion==0) {
                    String b = sendCmd("2");
                    if (b == null) {
                        Log.d("USB", "BTVERSION UKNKNOWN");
                        btversion = -1;
                    } else {
                        try {
                            btversion = Integer.parseInt(b);
                            Log.d("USB", "BTVERSION " + btversion);
                        } catch (NumberFormatException e) {};
                    }

                }


            }
        }

    }

    void eraseMem() {
        state = ERASE;

        String ret = sendCmd("e " + 65536);
        if (ret == null) {
            Log.d("USB", "Wrong response to erase cmd");
            return;
        }

        while (true) {
            ret = sendCmd("e");

            if (ret.compareTo("READY") == 0) break;

            int eraseMem = Integer.parseInt(ret);
            progress = (int) (100.0 * eraseMem / (8 * 1024 * 1024));

            Log.d("USB", "Erasing " + eraseMem);

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            LocalBroadcastManager.getInstance(myTestService).sendBroadcast(new Intent(BROADCAST_REFRESH));

        }

        Log.d("USB", "Erasing READY");

    }

//    void getSync() {
//        String ret = sendCmd("t");
//        if(ret==null) {
//            sync = -999999;
//            return;
//        }
//        String ss[] = ret.split(" ");
//        long now = System.currentTimeMillis() + myTestService.clockOffset;
//        sync=now -(long)(Long.parseLong(ss[0])/32.768);
//        Log.d("USB","Sync "+sync);
//    }
//
//    void setSync() {
//        long now = System.currentTimeMillis() + myTestService.clockOffset ;
//        long tt = (long) (now*32.768);
//        String ret = sendCmd("t "+tt);
//        Log.d("USB","SetSync "+ret);
//    }

    void readMem() {
        if (getMemPtr() != 0) return;

        if (memptr == 65536) {
            mHandler.post(new ToastRunnable("Device is empty"));
            return;
        }

        //List<SyncData> syncDataList = myTestService.obimonDatabase.syncDataDao().getSyncDataList();
        //Log.d("DUMP2", "SyncData size "+syncDataList.size());

        Log.d("DUMP2", "Memptr "+(memptr-65536));

        int n=0;
        int nn=0;
        int b=0;
        sendBinaryCmd("D");
        long statTime = System.currentTimeMillis();

        ByteBuffer buf = ByteBuffer.allocate(8*1024*1024);

        while(true) {

            DataBlock db=null;
            try {
                db = queue.poll(1000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            if(db==null) {
                Log.d("DUMP2", "Timeout");
                break;
            }

            int l = 128;

            if(db.b.length!=l) {
                Log.d("DUMP2", "Wrong response "+db.b.length);
                break;
            }

            buf.put(db.b);

            //Log.d("DUMP2", "n"+n );
            n++;
            nn+=l;
            //block.put(bret , 1, 64);

            progress = (int) (100.0*nn/memptr);


            long dt = System.currentTimeMillis() - statTime;
            if(dt>200) {
                double rate = 1000.0 * n / dt;
                double bps = 1000.0 * n * l * 8 / dt;

                Log.d("DUMP2", " rate "+rate+" bps "+bps+" q "+queue.size());
                n=0;
                statTime = System.currentTimeMillis();

                LocalBroadcastManager.getInstance(myTestService).sendBroadcast(new Intent(BROADCAST_REFRESH));
            }
        }

        Log.d("DUMP2", "Total "+nn+" "+buf.position());

        parseMem(buf);

    }

    void parseMem(ByteBuffer buf) {
        int len = buf.position();
        buf.rewind();

        SimpleDateFormat sdfDate = new SimpleDateFormat("yyyy-MM-dd");//dd/MM/yyyy
        Date now = new Date();
        String strDate = sdfDate.format(now);


        String fname = name+"_"+strDate+".txt";

        File fdir = new File(myTestService.getCacheDir(), "obicache");
        if(!fdir.exists()) fdir.mkdir();

        //File file = new File(myTestService.getExternalCacheDir(), fname);

        File file = new File(fdir, fname);

        Log.d("MAIL", "File "+file.getAbsolutePath());

        PrintWriter out=null;
        try {
            out = new PrintWriter(new FileWriter(file));
        } catch (IOException e) {
            e.printStackTrace();
        }

        //out.print(name+"\t"+mac.substring(9)+"\n");
        out.print(name+"\n");
        out.print("session\tdate\ttime\tunix_ts\tnS\tacc\n");


        Log.d("PARSEDUMP", "Dumping memptr "+memptr);
        boolean err = false;

        int n=0;
        long lastStat = System.currentTimeMillis();

        dumpSession=0;
        nSession=0;
        lastBlockTs=0;
        lastTs=0;

        for(int i=0; i<len; i+= 256) {
            byte[] b = new byte[256];
            buf.get(b, 0, 256);

            ByteBuffer block = ByteBuffer.wrap(b);

            if (parseDump(block, out) != 0) break;

            if(System.currentTimeMillis() - lastStat > 1000) {

                progress = (int) (100.0 * i / len);
                LocalBroadcastManager.getInstance(myTestService).sendBroadcast(new Intent(BROADCAST_REFRESH));

            }

                //Log.d("PARSEDUMP", "Remaining "+buf.remaining());

        }

        out.close();

        if(err) {
            mHandler.post(new ToastRunnable("Error reading data"));
            return;
        }

        emailIntent = new Intent(Intent.ACTION_SEND);
        // The intent does not have a URI, so declare the "text/plain" MIME type
        //emailIntent.setType(HTTP.PLAIN_TEXT_TYPE);
        emailIntent.setType("text/plain");
        //emailIntent.putExtra(Intent.EXTRA_EMAIL, new String[] {"jon@example.com"}); // recipients
        emailIntent.putExtra(Intent.EXTRA_SUBJECT, "Obimon "+fname);
        emailIntent.putExtra(Intent.EXTRA_TEXT, "Obimon data file");

        Uri fileURI = FileProvider.getUriForFile(MyActivity.context,
                "com.obimon.obimon_mobile.provider",
                file);

        Log.d("MAIL", "Path "+fileURI.getPath());

        emailIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); //?
        emailIntent.setData(fileURI);

        //emailIntent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file));
        emailIntent.putExtra(Intent.EXTRA_STREAM, fileURI);

        // You can also attach multiple items by passing an ArrayList of Uris
        //emailIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        sendEmail = true;
    }

    long FindOffset(long sess, long ts) {
        List<SyncData> syncDataList = myTestService.obimonDatabase.syncDataDao().getSyncDataList(sess);

        int n=0;
        long mindiff=0;

        SyncData mins = null;

        for(SyncData s : syncDataList) {
            long tdiff = abs(s.getTs() - ts);

            if(mins==null) {
                mins = s;
                mindiff = tdiff;
            }


            if(tdiff < mindiff ) {
                mins = s;
                mindiff = tdiff;
            }

            Log.d("DUMP2", "FindOffset sess="+sess+" devts="+s.getTs()+" tdiff="+tdiff+" off="+s.getOffset());

            //if(tdiff > 600*1000) {
            //    continue;
            //}

        }

        if(mins == null) return 0;

        Log.d("DUMP2", "FindOffset result devts=" + mins.getTs() + " offset:"+mins.getOffset());

        // now correct with ntp
        long off = mins.getOffset() + mins.getNtpcorr();

        return off;
    }

    int parseDump(ByteBuffer block, PrintWriter out) {

        block.rewind();
        block.order(ByteOrder.LITTLE_ENDIAN);

        int b = block.get(0) & 0xff;
        //Log.d("DUMP", "First byte "+b);

        if(b==254) Log.d("DUMP", "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX");

        long ts = block.getLong(); // read 8 bytes

        ts = (long) (ts/32.768);
        //Log.d("DUMP", " T:"+ts);

        int start = 8;

        b = block.get(8+3) & 0x80; //Little endian! So read last byte
        if(b == 0) {
            Log.d("DUMP", "OLD type, no session data "+Integer.toHexString(b & 0xFF));

            dumpSession=0;
            nSession=0;
            offset=0;

        } else {
            long l = block.getInt() & 0xffffffffL;

            if(l!=dumpSession) {
                dumpSession = l;
            }

            if(Math.abs(lastTs - ts)>=1000) {
                nSession++;

                Log.d("DUMP", "Session: "+dumpSession+" device block ts="+ts);

                // this is the first block for this session
                offset = FindOffset(dumpSession, ts);

                if(offset == -1) {
                    // could not find offset in database
                    offset = 0;
                }
            }

            start += 4;
        }

        lastBlockTs = ts;

        DateFormat formatter = new SimpleDateFormat("yyyy.MM.dd\tHH:mm:ss.SSS");

        for(int i=start; i<256; i+=4) {
            int d = block.getInt();

            int acc = (d >> 24) & 0xff;
            int g = d & 0x00ffffff;

            if(g == 0xffffff || g==0) continue; //ffffff

            long ts_corrected = ts + offset;

            Date date = new Date(ts_corrected);
            String dateFormatted = formatter.format(date);

            //Log.d("DUMP", ""+dateFormatted+"\t"+ts+"\t"+g);
            out.print(""+nSession+"\t"+dateFormatted+"\t"+ts_corrected+"\t"+g+"\t"+acc+"\n");

            ts+=1000/8;

            lastTs = ts;
        }

        //b+=64;

        //labelMem.setText("Loaded:"+p+"%");
        //labelMem.repaint();

        return 0;
    }

    void startThread() {
        if (manageUpdateThread == null) {
            manageUpdateThread = new UpdateThread();
            manageUpdateThread.start();
        }
    }

    void stopThread() {
        if(manageUpdateThread != null) {
            manageUpdateThread.stop = true;
            manageUpdateThread = null;
        }

    }

    private class ToastRunnable implements Runnable {
        String mText;

        public ToastRunnable(String text) {
            mText = text;
        }

        @Override
        public void run(){
            Toast.makeText(MyActivity.context, mText, Toast.LENGTH_SHORT).show();
        }
    }

}
