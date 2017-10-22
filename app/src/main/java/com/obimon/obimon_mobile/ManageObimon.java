package com.obimon.obimon_mobile;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;

import com.felhr.usbserial.CDCSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Vector;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.obimon.obimon_mobile.ManageObimon.ManageState.CHANGE_GROUP;
import static com.obimon.obimon_mobile.ManageObimon.ManageState.CHANGE_NAME;
import static com.obimon.obimon_mobile.ManageObimon.ManageState.CHANGE_ROLE;
import static com.obimon.obimon_mobile.ManageObimon.ManageState.ERASE;
import static com.obimon.obimon_mobile.ManageObimon.ManageState.IDLE;
import static com.obimon.obimon_mobile.ManageObimon.ManageState.RESET;
import static com.obimon.obimon_mobile.ManageObimon.ManageState.READMEM;
import static com.obimon.obimon_mobile.ManageObimon.ManageState.UNCONNECTED;
import static java.net.Proxy.Type.HTTP;

/**
 * Created by av on 2016.11.06..
 */

public class ManageObimon {
    public static final String BROADCAST_REFRESH = "BROADCAST_REFRESH";
    //MyActivity myActivity = null;

    //Context context=null;

    String name = null;
    String group = null;

    int role=-1;
    int newrole=-1;

    private Handler mHandler = new Handler();

    Intent emailIntent=null;
    boolean sendEmail=false;

    MyTestService myTestService = null;

    //byte bret[] = null;

    long memptr = 0;
    long sync = 0;

    int apiversion=-1;
    String build=null;

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
        UNCONNECTED, READMEM, ERASE, IDLE, CHANGE_NAME, CHANGE_GROUP, CHANGE_ROLE, RESET
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

//    String sendCmdOld(String cmd) {
//        Log.d("USB", "sendCmd: "+cmd);
//
//        if (serialPort == null) return null;
//        if (state == UNCONNECTED) return null;
//
//        bret = null;
//        serialPort.write(cmd.getBytes());
//        long start = System.currentTimeMillis();
//        while (bret == null && System.currentTimeMillis() < start + 1000) ;
//
//        if (bret == null) {
//            Log.d("USB", "No response");
//            return null;
//        }
//        if (bret.length == 0) {
//            Log.d("USB", "Empty response");
//            return null;
//        }
//
//        if (bret[0] != cmd.charAt(0)) {
//            Log.d("USB", "SendCMD wrong response "+((char)bret[0]));
//            bret = null;
//            return null;
//        }
//
//        String ret = null;
//        String s=null;
//        try {
//            s = new String(bret, "UTF-8");
//            ret = s.substring(2);
//        } catch (UnsupportedEncodingException e) {
//            e.printStackTrace();
//            bret = null;
//            return null;
//        }
//
//        Log.d("USB", "sendCmd ret="+s);
//
//        bret = null;
//        return ret;
//    }

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
                    readMem2();
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

                if (state == CHANGE_GROUP) {
                    Log.d("USB", "Change group: " + group);
                    sendCmd("o " + group);
                    state = IDLE;
                }

                if (state == CHANGE_ROLE) {

                    if(newrole==-1) {
                        Log.e("USB", "Wrong new role");
                        continue;
                    }

                    Log.d("USB", "Change role: " + newrole);
                    sendCmd("R " + newrole);

                    newrole = -1;

                    // read back
                    String s = sendCmd("R");
                    Log.d("USB", "Role " + s);
                    if(s!=null) {
                        role = Integer.parseInt(s);
                    }

                    state = IDLE;
                }

                if(state == UNCONNECTED) continue;
                getSync();

                if(Math.abs(sync)>30) {
                    setSync();
                }

                getMemPtr();

                String ret;

                if (group == null) {
                    ret = sendCmd("o");
                    group = ret;
                    Log.d("USB", "GROUP " + ret);
                }

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

                if(role == -1) {
                    String s = sendCmd("R");
                    Log.d("USB", "Role " + s);
                    if(s!=null) {
                        role = Integer.parseInt(s);
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

    void getSync() {
        String ret = sendCmd("t");
        if(ret==null) {
            sync = -999999;
            return;
        }
        String ss[] = ret.split(" ");
        long now = System.currentTimeMillis() + myTestService.clockOffset;
        sync=now -(long)(Long.parseLong(ss[0])/32.768);
        Log.d("USB","Sync "+sync);
    }

    void setSync() {
        long now = System.currentTimeMillis() + myTestService.clockOffset ;
        long tt = (long) (now*32.768);
        String ret = sendCmd("t "+tt);
        Log.d("USB","SetSync "+ret);
    }

    void readMem2() {
        if (getMemPtr() != 0) return;

        if (memptr == 65536) {
            mHandler.post(new ToastRunnable("Device is empty"));
            return;
        }

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

            int l = 255;

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

                Log.d("DUMP2", "rate "+rate+" bps "+bps+" q "+queue.size());
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

        String fname = name+"_"+group+"_"+strDate+".txt";

        File file = new File(myTestService.getExternalCacheDir(), fname);

        PrintWriter out=null;
        try {
            out = new PrintWriter(new FileWriter(file));
        } catch (IOException e) {
            e.printStackTrace();
        }

        out.print(name+"\t"+group+"\n");

        Log.d("PARSEDUMP", "Dumping memptr "+memptr);
        boolean err = false;

        int n=0;
        long lastStat = System.currentTimeMillis();

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
        emailIntent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file));
        // You can also attach multiple items by passing an ArrayList of Uris
        emailIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        //MyActivity.context.startActivity(Intent.createChooser(emailIntent, "Your email id"));
        //MyActivity.context.startActivity(emailIntent);

        sendEmail = true;
    }

    int parseDump(ByteBuffer block, PrintWriter out) {

        block.rewind();
        block.order(ByteOrder.LITTLE_ENDIAN);

        int b = block.get(0) & 0xff;
        //Log.d("DUMP", "First byte "+b);

        if(b==254) Log.d("DUMP", "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX");

        long ts = block.getLong();

        ts = (long) (ts/32.768);
        //Log.d("DUMP", " T:"+ts);

        //ts += 1000000000;

        DateFormat formatter = new SimpleDateFormat("yyyy.MM.dd\tHH:mm:ss.SSS");

        for(int i=8; i<256; i+=4) {
            int g = block.getInt();

            Date date = new Date(ts);
            String dateFormatted = formatter.format(date);

            //Log.d("DUMP", ""+dateFormatted+"\t"+ts+"\t"+g);
            out.print(""+dateFormatted+"\t"+ts+"\t"+g+"\n");

            ts+=1000/8;
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
        manageUpdateThread.stop = true;
        manageUpdateThread = null;

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
