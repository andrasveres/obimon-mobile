package com.obimon.obimon_mobile;

import android.Manifest;
import android.app.ActionBar;
import android.app.FragmentTransaction;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.ViewPager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

import com.felhr.usbserial.CDCSerialDevice;
import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;

import org.acra.ACRA;
import org.acra.ReportingInteractionMode;
import org.acra.annotation.ReportsCrashes;
import org.acra.config.ACRAConfiguration;
import org.acra.config.ConfigurationBuilder;

import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

//@ReportsCrashes(mailTo = "kakukk71@gmail.com",
//        mode = ReportingInteractionMode.TOAST,
//        resToastText = R.string.crash_toast_text)

public class MyActivity extends FragmentActivity implements ActionBar.TabListener {
    private static final String TAG = "MyActivity";

    //public MyTestReceiver receiverForTest;
    AppSectionsPagerAdapter mAppSectionsPagerAdapter;
    ViewPager mViewPager;

    static boolean adjustScl=true;
    static int sclWindow = 60;
    static int graphHistory = 30;

    static String group = "";

    public static MyTestService myTestService=null;
    public static Context context = null;

//    UsbDeviceConnection usbConnection;
//    static UsbManager usbManager;

    //ManageObimon manageObimon = new ManageObimon(this);
    static boolean usbReceiverRegistered = false;

    PendingIntent mPermissionIntent;
    //Vector<Integer> data;


    @Override
    public void onTabSelected(ActionBar.Tab tab, FragmentTransaction ft) {
        mViewPager.setCurrentItem(tab.getPosition());
    }

    @Override
    public void onTabUnselected(ActionBar.Tab tab, FragmentTransaction ft) {

    }

    @Override
    public void onTabReselected(ActionBar.Tab tab, FragmentTransaction ft) {

    }

    public void startMyTestService() {
        Log.i(TAG, "-------------------- startService");

        ShowNotification();

        if(myTestService!=null) {
            Log.i(TAG, "Service already running, ignoring");
        }

        Intent i = new Intent(this, MyTestService.class);
        i.putExtra("foo", "bar");

        startService(i);

    }

    public void stopMyTestService() {
        Log.i(TAG, "-------------------- stopService");

        HideNotification();

        if(myTestService==null) {
            Log.i(TAG, "Service not running, ignoring");
        }

        Intent i = new Intent(this, MyTestService.class);
        i.putExtra("foo", "bar");

        stopService(i);

        //myTestService = null;

    }

    void ShowNotification() {
        Bitmap bm = Bitmap.createScaledBitmap(BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher),
                getResources().getDimensionPixelSize(android.R.dimen.notification_large_icon_width),
                getResources().getDimensionPixelSize(android.R.dimen.notification_large_icon_height),
                true);
        Intent intent = new Intent(this, MyActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_CANCEL_CURRENT);
        Notification.Builder builder = new Notification.Builder(getApplicationContext());
        builder.setContentTitle("Obimon is running");
        //builder.setContentText("This is the text");
        //builder.setSubText("Some sub text");
        //builder.setNumber(101);
        builder.setContentIntent(pendingIntent);
        builder.setTicker("Obimon service running");
        builder.setSmallIcon(R.mipmap.ic_launcher);
        //builder.setLargeIcon(bm);
        //builder.setAutoCancel(true);
        builder.setPriority(Notification.PRIORITY_HIGH);
        builder.setOngoing(true);
        Notification notification = builder.build();
        NotificationManager notificationManger =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManger.notify(1, notification);
    }

    void HideNotification() {
        NotificationManager notificationManger =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManger.cancel(1);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my);

        Log.i(TAG, "MyActivity onCreate");

        context = getApplicationContext();

        // disable sleep
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        SharedPreferences settings = getSharedPreferences("settings", 0);
        sclWindow = settings.getInt("scl_window", 60);
        graphHistory = settings.getInt("graph_history", 30);
        group = settings.getString("group", "nogroup");


        //XYSeriesRenderer renderer = new XYSeriesRenderer();
        //renderer.setLineWidth(2);
        //renderer.setColor(Color.RED);

        // Create the adapter that will return a fragment for each of the three primary sections
        // of the app.
        mAppSectionsPagerAdapter = new AppSectionsPagerAdapter(getSupportFragmentManager());

        // Set up the action bar.
        final ActionBar actionBar = getActionBar();

        // Specify that the Home/Up button should not be enabled, since there is no hierarchical
        // parent.
        actionBar.setHomeButtonEnabled(false);

        // Specify that we will be displaying tabs in the action bar.
        actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);

        // Set up the ViewPager, attaching the adapter and setting up a listener for when the
        // user swipes between sections.
        mViewPager = (ViewPager) findViewById(R.id.pager);
        mViewPager.setAdapter(mAppSectionsPagerAdapter);
        mViewPager.setOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                // When swiping between different app sections, select the corresponding tab.
                // We can also use ActionBar.Tab#select() to do this if we have a reference to the
                // Tab.
                actionBar.setSelectedNavigationItem(position);
            }
        });

        // For each of the sections in the app, add a tab to the action bar.
        for (int i = 0; i < mAppSectionsPagerAdapter.getCount(); i++) {
            // Create a tab with text corresponding to the page title defined by the adapter.
            // Also specify this Activity object, which implements the TabListener interface, as the
            // listener for when this tab is selected.
            actionBar.addTab(
                    actionBar.newTab()
                            .setText(mAppSectionsPagerAdapter.getPageTitle(i))
                            .setTabListener(this));
        }

        //data = new Vector<>();

        Log.i("XXXX", "ChartView added");

        // in case of android 6 we have to first ask for persmission before activating BLE scan

        BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null) {
            // Device does not support Bluetooth
            Log.i("XXXX", "Bluetooth not supported");
        }

        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, 1);
        }

        // Bluetooth permission requests
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    123);
        } else {
            startMyTestService();
        }

        //     myTestService.manageObimon.context = getApplicationContext();

//        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
//
//        Log.d("USB", "MyActivity: "+this);
//
//        if(!usbReceiverRegistered) {
//            //mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
//            IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
//            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
//            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
//            //filter.addAction("android.hardware.usb.action.USB_DEVICE_ATTACHED");
//            //filter.addAction("android.hardware.usb.action.USB_DEVICE_DETACHED");
//            registerReceiver(mUsbReceiver, filter);
//            Log.d("USB", "RegisterReceiver");
//
//            usbReceiverRegistered = true;
//        }
//
//        checkUSBDevices();

        //throw new RuntimeException("This is a crash");
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode, @NonNull final String[] permissions, @NonNull final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 123) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted.
                Log.d(TAG,"Persmission granted");
                startMyTestService();

            } else {
                // User refused to grant permission.
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.i(TAG, "MyActivity onStart");

    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_my, menu);
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        Log.i(TAG, "onDestroy==IGNORE");

    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.i(TAG, "onPause==IGNORE");

        //receiverForTest.setReceiver(null);
    }

    @Override
    protected void onResume() {
        super.onResume();

        //setupServiceReceiver();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_stop) {
            stopMyTestService();

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            finish();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    public static class AppSectionsPagerAdapter extends FragmentPagerAdapter {

        public AppSectionsPagerAdapter(FragmentManager fm) {
            super(fm);
        }

        @Override
        public Fragment getItem(int i) {
            switch (i) {
                //case 0:
                //    return new SelectFragment();

                case 0:
                    return new DevicesFragment();

                case 1:
                    return new ChartFragment();

                case 2:
                    return new SettingsFragment();

                default:
                    return new ManageFragment();
            }
        }

        @Override
        public int getCount() {
            return 4;
        }

        @Override
        public CharSequence getPageTitle(int position) {
            switch(position) {
                //case 0:
                //    return "Select";
                case 0:
                    return "Obimons";
                case 1:
                    return "Chart";
                case 2:
                    return "Settings";
                default:
                    return "Manage";
            }

        }
    }

/*
    */
/**
     * A fragment that launches other parts of the demo application.
     *//*

    public static class LaunchpadSectionFragment extends Fragment {

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {
            View rootView = inflater.inflate(R.layout.fragment_section_devices, container, false);

            // Demonstration of a collection-browsing activity.
            rootView.findViewById(R.id.demo_collection_button)
                    .setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            Intent intent = new Intent(getActivity(), CollectionDemoActivity.class);
                            startActivity(intent);
                        }
                    });

            // Demonstration of navigating to external activities.
            rootView.findViewById(R.id.demo_external_activity)
                    .setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            // Create an intent that asks the user to pick a photo, but using
                            // FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET, ensures that relaunching
                            // the application from the device home screen does not return
                            // to the external activity.
                            Intent externalActivityIntent = new Intent(Intent.ACTION_PICK);
                            externalActivityIntent.setType("image*/
/*");
                            externalActivityIntent.addFlags(
                                    Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
                            startActivity(externalActivityIntent);
                        }
                    });

            return rootView;
        }
    }
*/

    /**
     * A fragment that launches other parts of the demo application.
     */





}
