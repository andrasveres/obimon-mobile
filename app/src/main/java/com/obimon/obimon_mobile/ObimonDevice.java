package com.obimon.obimon_mobile;

import android.annotation.TargetApi;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.graphics.Color;
import android.os.Build;
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
import java.util.List;
import java.util.UUID;

//import de.tavendo.autobahn.WebSocketConnection;
//import de.tavendo.autobahn.WebSocketException;
//import de.tavendo.autobahn.WebSocketHandler;

/**
 * Created by andrasveres on 04/04/15.
 */
public class ObimonDevice {
    String TAG = "ObimonDevice";

    BluetoothDevice device;
    String addr=null;
    String name="unknown", build="unknown", group="unknown";
    int apiversion=-1;
    int color;

    MyTestService myTestService;
//    HttpClient httpClient = new DefaultHttpClient();
//    private final WebSocketConnection mWSConnection = new WebSocketConnection();
    WebSocketClient mWebSocketClient;

    HousekeepingThread housekeepingThread;
    BluetoothGatt mGatt;


    ConnectionState connectionState=ConnectionState.IDLE;
    ConnectionState wsConnectionState=ConnectionState.IDLE;

    TimeSeries series;
    int sHist = 10; // history to show

    int lastId = -1;

    boolean selected = false;

    int received=0;
    int lost=0;

    long lastTsBroadcast = 0;
    long lastSeen=0;
    long lastGsrTime =0;

    double lastGsr=0;
    double scl=0;

    double bat=0;
    int mem=0;
    int signal=0;
    double sync=Double.MAX_VALUE;

    public enum ConnectionState {
        IDLE, CONNECTING, CONNECTED, FAILED
    }

    void stopObimonDevice() {

        Log.d(TAG, "stopObimonDevice: ");
        if(mGatt!=null) {
            mGatt.close();
            mGatt=null;
        }
        connectionState = ConnectionState.IDLE;
        series.clear();
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

    void AddData(int id, double d) {

        double gsr = d / 1000.0;

        if(lastGsr == -0 ) {
            scl = gsr;
        }

        //if(Math.abs(gsr-lastGsr)>0.5) {
            // jump
        //    scl = gsr;
        //    Log.i(TAG, "Jump "+gsr+" "+lastGsr);
        //}

        double alpha = 1.0/(MyActivity.sclWindow*8);
        scl = alpha * gsr + (1-alpha) * scl;

        double v=gsr;

        if(MyActivity.adjustScl) v -= scl;


        series.add(System.currentTimeMillis(), v);

        if(mWebSocketClient!=null)
            if(mWebSocketClient.getConnection().isOpen()) mWebSocketClient.send("g "+(int)(gsr*1000));

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

    /*
    public void connectObimon() {
        Log.i("XXXX", "ConnectObimon " + name);

        connectionState = ConnectionState.CONNECTING;

        if (mGatt == null) {
            // autoconnect as soon as device is available
            mGatt = device.connectGatt(myTestService, true, gattCallback);
        }

    }
*/

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
