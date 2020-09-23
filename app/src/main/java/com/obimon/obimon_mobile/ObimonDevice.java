package com.obimon.obimon_mobile;

import android.annotation.TargetApi;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;

import org.achartengine.model.TimeSeries;
import org.achartengine.renderer.XYSeriesRenderer;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_17;
import org.java_websocket.handshake.ServerHandshake;

/*
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
*/

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
//import java.util.Queue;
import java.util.Queue;
import java.util.UUID;

import static android.bluetooth.BluetoothGattCharacteristic.FORMAT_UINT32;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_INDICATE;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ;

//import de.tavendo.autobahn.WebSocketConnection;
//import de.tavendo.autobahn.WebSocketException;
//import de.tavendo.autobahn.WebSocketHandler;

/**
 * Created by andrasveres on 04/04/15.
 */
public class ObimonDevice {
    public BluetoothGattCharacteristic gattCharacteristic_gsr;
    public BluetoothGattCharacteristic gattCharacteristic_name;
    public BluetoothGattCharacteristic gattCharacteristic_tick;

    private Queue<Runnable> commandQueue = new LinkedList<>();
    private boolean commandQueueBusy;
    Handler bleHandler = new Handler();

    int nrTries=0;
    boolean isRetrying=false;
    int MAX_TRIES = 10;

    String TAG = "ObimonDevice";

    BluetoothDevice device;
    BluetoothGatt mBluetoothGatt;

    String addr=null;
    String name="wait", build="wait";
    int apiversion=-1;
    int color;

    MyTestService myTestService;
//    HttpClient httpClient = new DefaultHttpClient();
//    private final WebSocketConnection mWSConnection = new WebSocketConnection();
    WebSocketClient mWebSocketClient;

    HousekeepingThread housekeepingThread;


    ConnectionState connectionState=ConnectionState.IDLE;
    ConnectionState wsConnectionState=ConnectionState.IDLE;

    TimeSeries series;
    TimeSeries seriesAcc;

    int sHist = 10; // history to show

    int lastId = -1;

    boolean selected = false;

    int received=0;
    int lost=0;

    long lastTsBroadcast = 0;
    long lastSeen=0;
    long lastGsrTime =0;
    long lastCharacteristics =0;
    long session=0;

    double lastGsr=0;
    double scl=0;

    double bat=-1;
    int mem=-1;
    int signal=0;
    long lastSessionSync=0;
    long prevOffset=0;

    public enum ConnectionState {
        IDLE, CONNECTING, CONNECTED, FAILED
    }

    void stopObimonDevice() {

        Log.d(TAG, "stopObimonDevice: ");
        if(mBluetoothGatt!=null) {
            mBluetoothGatt.close();
            mBluetoothGatt=null;
        }
        //connectionState = ConnectionState.IDLE;
        series.clear();
        seriesAcc.clear();
    }



    ObimonDevice(MyTestService service, BluetoothDevice device) {
        this.device = device;
        myTestService = service;

//        // WORKAROUND
//        if(name==null) {
//            Log.i("ObimonDevice", "Name workaround: "+name);
//        }
        addr = device.getAddress();
        name = addr.substring(9);

        series = new TimeSeries(this.name); //device.getName());
        seriesAcc = new TimeSeries(this.name+"_acc"); //device.getName());

        housekeepingThread = new HousekeepingThread();
        //housekeepingThread.start();                                      ANDRAS

    }

    void CalcLoss(int id) {
        if (lastId != -1) {
            if (id > lastId) lost += id - lastId - 1;
        }

        lastId = id;
        received++;
    }

    void AddData(long ts, double d, int acc) {

        double gsr = d / 1000.0;

        if(lastGsr == -0 ) {
            scl = gsr;
        }

        //if(Math.abs(gsr-lastGsr)>0.5) {
            // jump
        //    scl = gsr;
        //    Log.i(TAG, "Jump "+gsr+" "+lastGsr);
        //}

        if(series.getItemCount()==0) scl = gsr;
        else {
            double dt = (ts - lastGsrTime)/1000.0;

            double alpha = 1.0 / MyActivity.sclWindow * dt;

            // Log.d("DDD", "dt "+dt+" alpha "+alpha);

            scl = alpha * gsr + (1 - alpha) * scl;
        }

        double v=gsr;

        if(MyActivity.adjustScl) v -= scl;


        series.add(ts, v);
        seriesAcc.add(ts, acc);


        //if(mWebSocketClient!=null)
        //    if(mWebSocketClient.getConnection().isOpen()) mWebSocketClient.send("g "+(int)(gsr*1000));

        lastGsr = gsr;
/*
        while(true) {
            //Log.d(TAG, "AddData: "+series.getItemCount());
            if(series.getItemCount()==0) break;
            if (series.getX(0) < System.currentTimeMillis() - 1000 * sHist) series.remove(0);
            else break;
        }
  */      //Log.i("XXXX", "AddData called "+d+" x:"+x);
    }



    void connected() {
        connectionState = ConnectionState.CONNECTED;
    }

    /*
    void postGsr(int gsr) {

        if(true) return;

        String url = "http://obimon.com:8080/ObimonServer/Obimon";

        Log.i("XXXX", "POST GSR "+url);

        HttpPost post = new HttpPost(url);

        // add header
        //post.setHeader("User-Agent", USER_AGENT);

        List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
        urlParameters.add(new BasicNameValuePair("gsr", ""+gsr));

        try {
            post.setEntity(new UrlEncodedFormEntity(urlParameters));
        } catch (UnsupportedEncodingException e) {
            Log.i("XXXX", "Post error:" + e.getMessage());
            e.printStackTrace();
        }

        HttpResponse response = null;
        try {
            response = httpClient.execute(post);
        } catch (IOException e) {
            Log.i("XXXX", "Post error:" + e.getMessage());
            e.printStackTrace();
            return;
        }

        System.out.println("\nSending 'POST' request to URL : " + url);
        System.out.println("Post parameters : " + post.getEntity());
        System.out.println("Response Code : " +
                response.getStatusLine().getStatusCode());

    }
*/

    /*
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Log.i("onConnectionStateChange", "Status: " + status);
            switch (newState) {
                case BluetoothProfile.STATE_CONNECTED:
                    Log.i("gattCallback", "STATE_CONNECTED");
                    gatt.discoverServices();
                    connected();
                    break;

                case BluetoothProfile.STATE_DISCONNECTED:
                    Log.e("gattCallback", "STATE_DISCONNECTED");
                    //mGatt.close();

                    connectionState = ConnectionState.IDLE;

                    break;
                default:
                    Log.e("gattCallback", "STATE_OTHER");
            }

        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            List<BluetoothGattService> services = gatt.getServices();
            Log.i(TAG, "number of services:" + services.size());

            for (BluetoothGattService s : services) {


                List<BluetoothGattCharacteristic> chars = s.getCharacteristics();
                Log.d(TAG, "service: " + s.getUuid() + " type:" + s.getType() + " num_char:" + chars.size());

                for (BluetoothGattCharacteristic c : chars) {
                    Log.d(TAG, "characteristics: " + c.getUuid() + " properties:" + c.getProperties());
                }
            }

            BluetoothGattService gsrService = gatt.getService(UUID.fromString("43974957-3487-9347-5977-654321987654"));
            BluetoothGattCharacteristic gsrChar = gsrService.getCharacteristic(UUID.fromString("43789734-9798-3479-8347-983479887878"));

            List<BluetoothGattDescriptor> descriptors = gsrChar.getDescriptors();
            for (BluetoothGattDescriptor descr : descriptors) {
                Log.i("descriptor", "" + descr.getUuid() + " " + descr.getPermissions());
            }

            //gatt.readCharacteristic(gsrChar);
            gatt.setCharacteristicNotification(gsrChar, true);


            // 0x2902 org.bluetooth.descriptor.gatt.client_characteristic_configuration.xml
            UUID uuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
            BluetoothGattDescriptor descriptor = gsrChar.getDescriptor(uuid);
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            //descriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
            gatt.writeDescriptor(descriptor);

        }

        @Override
        // Characteristic notification
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {

            byte[] data = characteristic.getValue();
            Log.i(TAG, "onCharacteristicChanged: len:" + data.length);

            final StringBuilder stringBuilder = new StringBuilder(data.length);

            int gsr = 0;
            for (byte byteChar : data) {
                stringBuilder.append(String.format("%02X ", byteChar));

                gsr *= 256;
                gsr += (byteChar & 0x000000ff);
            }

            //gsr = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16, 0);

            //AddData(0, gsr);

            Log.i("byte", "" + stringBuilder.toString());

        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic
                                                 characteristic, int status) {
            Log.i("onCharacteristicRead", "" + characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16, 0));

            byte[] data = characteristic.getValue();

            final StringBuilder stringBuilder = new StringBuilder(data.length);
            for (byte byteChar : data)
                stringBuilder.append(String.format("%02X ", byteChar));

            Log.i("byte", "" + stringBuilder.toString());

        }
    };
*/

/*
    private void connectWebSocket() {

        if(wsConnectionState == ConnectionState.CONNECTED || wsConnectionState == ConnectionState.CONNECTING) {
            Log.i("XXXX", "WS already connected");
            return;
        }
        Log.i("XXXX", "WS CONNECT==================");

        wsConnectionState = ConnectionState.CONNECTING;

        try {
            mWSConnection.connect("ws://affektiv.hu:8080/ObimonServer/stream", new WebSocketHandler() {

                @Override
                public void onOpen() {
                    Log.d("XXXX", "WS Status: Connected");
                    // mWSConnection.sendTextMessage("Hello, world!");
                    wsConnectionState = ConnectionState.CONNECTED;

                }

                @Override
                public void onTextMessage(String payload) {
                    Log.d("XXXX", "WS Got msg: " + payload);
                }

                @Override
                public void onClose(int code, String reason) {
                    Log.d("XXXX", "WS Connection lost.");

                    wsConnectionState = ConnectionState.FAILED;

                }
            });
        } catch (WebSocketException e) {

            Log.d("XXXX", e.toString());

            wsConnectionState = ConnectionState.FAILED;
        }
    }


    public void sendWebSocket(String s) {
        //if(mWSConnection.isConnected())
        if(wsConnectionState == ConnectionState.CONNECTED)
            mWSConnection.sendTextMessage(s);
    }
*/

    void enableNotification(BluetoothGattCharacteristic c) {

        if(mBluetoothGatt == null) {
            Log.e("BBB", "ERROR: Gatt is 'null', ignoring read request");
            return;
        }

        // Check if characteristic is valid
        if(c == null) {
            Log.e("BBB", "ERROR: Characteristic is 'null', ignoring read request");
            return;
        }

        //Log.d("BBB", "enableNotification "+addr+" "+c.getUuid());

        boolean res = mBluetoothGatt.setCharacteristicNotification(c, true);
        //Log.d("BBB", "setCharacteristicNotification " + res);

        List<BluetoothGattDescriptor> descriptors = c.getDescriptors();
        for (BluetoothGattDescriptor d : descriptors) {
            //Log.d("BBB", "desc " + d.getUuid());
            d.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
            res = mBluetoothGatt.writeDescriptor(d);

            //Log.d("BBB", "setCharacteristicNotification write desc " + res);

            if(res) {
                //Log.d("BBB", "setCharacteristicNotification write desc SUCCESS " +addr);
                return;
            }
        }


        Log.d("BBB", "enableNotification FAILED " +addr);

    }

    public boolean setNotify(final BluetoothGattCharacteristic characteristic, final boolean enable) {
        //Log.d("BBB", "setNotify");

        // Check if characteristic is valid

        // Queue Runnable to turn on/off the notification now that all checks have been passed
        boolean result = commandQueue.add(new Runnable() {
            @Override
            public void run() {
                enableNotification(characteristic);
            }
        });

        if(result) {
            nextCommand();
        } else {
            Log.e("BBB", "ERROR: Could not enqueue write command");
        }

        return result;
    }

    public boolean readCharacteristic(final BluetoothGattCharacteristic characteristic) {
        Log.d("BBB", "readCharacteristic "+addr);

        if(mBluetoothGatt == null) {
            Log.e("BBB", "ERROR: Gatt is 'null', ignoring read request");
            return false;
        }

        // Check if characteristic is valid
        if(characteristic == null) {
            Log.e("BBB", "ERROR: Characteristic is 'null', ignoring read request");
            return false;
        }

        // Check if this characteristic actually has READ property
        if((characteristic.getProperties() & PROPERTY_READ) == 0 ) {
            Log.e("BBB", "ERROR: Characteristic cannot be read");
            return false;
        }

        // Enqueue the read command now that all checks have been passed
        boolean result = commandQueue.add(new Runnable() {
            @Override
            public void run() {
                if(!mBluetoothGatt.readCharacteristic(characteristic)) {
                    Log.e("BBB", String.format("ERROR: readCharacteristic failed for characteristic: %s", characteristic.getUuid()));
                    completedCommand();
                } else {
                    Log.d("BBB", String.format("reading characteristic <%s>", characteristic.getUuid()));
                    nrTries++;
                }
            }
        });

        if(result) {
            nextCommand();
        } else {
            Log.e("BBB", "ERROR: Could not enqueue read characteristic command");
        }
        return result;
    }


    private void nextCommand() {
        // If there is still a command being executed then bail out
        if(commandQueueBusy) {
            //Log.d("BBB", "commandqueue busy "+addr);
            return;
        }

        // Check if we still have a valid gatt object
        if (mBluetoothGatt == null) {
            Log.e("BBB", String.format("ERROR: GATT is 'null' for peripheral '%s', clearing command queue", addr));
            commandQueue.clear();
            commandQueueBusy = false;
            return;
        }

        // Execute the next command in the queue
        if (commandQueue.size() > 0) {
            final Runnable bluetoothCommand = commandQueue.peek();
            commandQueueBusy = true;
            nrTries = 0;

            bleHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        bluetoothCommand.run();
                    } catch (Exception ex) {
                        Log.e("BBB", String.format("ERROR: Command exception for device '%s'", addr), ex);
                    }
                }
            });
        }
    }

    private void completedCommand() {
        commandQueueBusy = false;
        isRetrying = false;
        commandQueue.poll();
        nextCommand();
    }

    private void retryCommand() {
        commandQueueBusy = false;
        Runnable currentCommand = commandQueue.peek();
        if(currentCommand != null) {
            if (nrTries >= MAX_TRIES) {
                // Max retries reached, give up on this one and proceed
                Log.v("BBB", "Max number of tries reached");
                commandQueue.poll();
            } else {
                isRetrying = true;
            }
        }
        nextCommand();
    }

    void ProcessSyncData(long ses, long tick, long ts) {
        session = ses;

        long offset = ts - tick;

        Log.d("BBB", "SESSION " + addr + " "+name+" tick="+ tick+ " session="+session+" ====================== offset " +offset+" ntpcorr "+myTestService.ntpCorr);

        if (Math.abs(offset - prevOffset) < 100) {
            Log.d("BBB", "Saving offset "+addr);
            myTestService.SaveSessionData(ObimonDevice.this, System.currentTimeMillis(), session, tick, offset);
        } else {
            Log.d("BBB", "Too large offset diff "+addr);
        }

        prevOffset = offset;
    }

    public final static String ACTION_GATT_CONNECTED = "com.obimon.obimon_mobile.ACTION_GATT_CONNECTED";
    //get callbacks when something changes
    BluetoothGattCallback mGattCallback=new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {

            if(status != BluetoothGatt.GATT_SUCCESS) {
                Log.d("BBB", "gatt conn FAILED "+addr);

                connectionState = ObimonDevice.ConnectionState.IDLE;

                return;

            }

            if (newState== BluetoothProfile.STATE_CONNECTED){
                //device connected
                connectionState = ObimonDevice.ConnectionState.CONNECTED;

                Log.d("BBB", "STATE_CONNECTED "+addr);

                //final Intent intent = new Intent(ACTION_GATT_CONNECTED);
                //sendBroadcast(intent);

                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {}

                mBluetoothGatt.discoverServices();

            } else if(newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("BBB", "STATE_DISCONNECTED "+addr);

                connectionState = ObimonDevice.ConnectionState.IDLE;

            } else {
                Log.d("BBB", "XXXXXXXXX "+addr);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {

            if (status==BluetoothGatt.GATT_SUCCESS){
                //all Services have been discovered


                Log.d("BBB", "onServicesDiscovered "+addr);

                // 43974957348793475977654321987654
                UUID serviceUUID =  UUID.fromString("43974957-3487-9347-5977-654321987654");
                BluetoothGattService gattService = gatt.getService(serviceUUID);

                List<BluetoothGattCharacteristic> chars = gattService.getCharacteristics();

                UUID charUUID_gsr =         UUID.fromString("43789734-9798-3479-8347-983479887878");
                UUID charUUID_name =        UUID.fromString("43789734-9798-3479-8347-98347988787b");
                UUID charUUID_sessionid =   UUID.fromString("43789734-9798-3479-8347-98347988787c");
                UUID charUUID_tick =        UUID.fromString("43789734-9798-3479-8347-98347988787d");

                for(BluetoothGattCharacteristic c : chars) {
                    UUID u = c.getUuid();
                    //Log.d("BBB", "Found chars: "+u.toString());

                }

                gattCharacteristic_gsr = gattService.getCharacteristic(charUUID_gsr);
                gattCharacteristic_name = gattService.getCharacteristic(charUUID_name);
                gattCharacteristic_tick = gattService.getCharacteristic(charUUID_tick);

                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {}

                if(gattCharacteristic_gsr!=null) setNotify(gattCharacteristic_gsr, true);
                if(gattCharacteristic_tick!=null) setNotify(gattCharacteristic_tick, true);
                if(gattCharacteristic_name!=null) setNotify(gattCharacteristic_name, true);

                //readCharacteristic(gattCharacteristic_name);
                //readCharacteristic(gattCharacteristic_tick);



                //enableNotification(gattCharacteristic_gsr);
                //waitForGattAnswer();

//                gattAnswer=0;
//                enableNotification(gattCharacteristic_tick);
//                waitForGattAnswer();
//
//                gattAnswer=0;
//                mBluetoothGatt.readCharacteristic(gattCharacteristic_name);
//                waitForGattAnswer();
//
//                gattAnswer=0;
//                mBluetoothGatt.readCharacteristic(gattCharacteristic_sessionid);
//                waitForGattAnswer();

            } else {
                Log.d("BBB", "onServicesDiscovered "+addr+" ERROR");

            }
        }



        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {

            //we are still connected to the service
            if (status==BluetoothGatt.GATT_SUCCESS){
                //send the characteristic to broadcastupdate
                //broadcastupdate(ACTION_DATA_AVAILABLE, characteristic);

                if(characteristic== gattCharacteristic_name) {

                    String s = characteristic.getStringValue(0);
                    if(s.length()>0) name = s;
                    Log.d("BBB", "Char read name "+addr+" "+characteristic.getStringValue(0));


                } else {
                    Log.d("BBB", "onCharacteristicRead UNKNOWN " + addr);
                }
                completedCommand();

            } else {
                Log.d("BBB", "onCharacteristicRead FAILED "+addr);
                completedCommand();
            }

        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            //send the characteristic to broadcastupdate
            //broadcastupdate(ACTION_DATA_AVAILABLE, characteristic);

            //Log.d("BBDATA", "onCharacteristicChanged "+obi.addr);

            if(characteristic== gattCharacteristic_tick) {

                long ses = myTestService.ParseSession(characteristic.getValue(), 0);
                long tick = myTestService.ParseTick(characteristic.getValue(), 4);

                long offset = System.currentTimeMillis() - tick;

                Log.d("BBB", "SESSION from NOIFY "+addr);

                ProcessSyncData(ses, tick, System.currentTimeMillis());


                //Log.d("BBB", "Char notify tick "+addr+" "+characteristic.getValue().toString());

            } else if(characteristic == gattCharacteristic_gsr) {

                byte[] buf = characteristic.getValue();
                if (buf.length != 4) return;

                //int acc = buf[0];

                int acc = myTestService.ParseAcc(buf,0);
                int gsr = myTestService.ParseGsr(buf, 1);

                //Log.d("BBDATA", "GSRDATA " + acc + " " + gsr);

                AddData(System.currentTimeMillis(), gsr, acc);

                lastCharacteristics = System.currentTimeMillis();
                lastGsrTime = System.currentTimeMillis();
                received++;
                lastSeen = System.currentTimeMillis();

            } else if(characteristic== gattCharacteristic_name) {

                String s = characteristic.getStringValue(0);
                if(s.length()>0) name = s;
                Log.d("BBB", "Char notify name "+addr+" "+characteristic.getStringValue(0));


            }

        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            //Log.d("BBB", "onDescriptorWrite");

            completedCommand();        }
    };

    class HousekeepingThread extends Thread {
        boolean stop = false;

        @Override
        public void run() {

            Log.i("HousekeepingThread", "Started");
 
            while(!stop) {

                if(wsConnectionState == ConnectionState.IDLE || wsConnectionState == ConnectionState.FAILED) {
                    Log.i("HousekeepingThread", "WS RECONNECT");
                    connectWebSocket();
                }

                try {
                    sleep(2000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

            }
        }
    }


    private void connectWebSocket() {
        Log.i("connectWebSocket", "connecting");

        URI uri;
        try {
            uri = new URI("ws://affektiv.hu:8080/ObimonServer/stream");
        } catch (URISyntaxException e) {
            wsConnectionState = ConnectionState.FAILED;
            e.printStackTrace();
            return;
        }

        mWebSocketClient = new WebSocketClient(uri, new Draft_17()) {
            @Override
            public void onOpen(ServerHandshake serverHandshake) {
                Log.i("Websocket", "Opened");
                wsConnectionState = ConnectionState.CONNECTED;
                mWebSocketClient.send("Hello from " + Build.MANUFACTURER + " " + Build.MODEL);
            }

            @Override
            public void onMessage(String s) {
                final String message = s;
                Log.i("Websocket", "onMessage "+s);
            }

            @Override
            public void onClose(int i, String s, boolean b) {
                Log.i("Websocket", "Closed " + s);
                wsConnectionState = ConnectionState.FAILED;
            }

            @Override
            public void onError(Exception e) {
                Log.i("Websocket", "Error " + e.getMessage());
            }
        };
        mWebSocketClient.connect();
    }
}
