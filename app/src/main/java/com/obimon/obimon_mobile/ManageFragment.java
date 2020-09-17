package com.obimon.obimon_mobile;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Bundle;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import static com.obimon.obimon_mobile.ManageObimon.ManageState.CHANGE_NAME;
import static com.obimon.obimon_mobile.ManageObimon.ManageState.ERASE;
import static com.obimon.obimon_mobile.ManageObimon.ManageState.IDLE;
import static com.obimon.obimon_mobile.ManageObimon.ManageState.READMEM;
import static com.obimon.obimon_mobile.ManageObimon.ManageState.RESET;
import static com.obimon.obimon_mobile.ManageObimon.ManageState.UNCONNECTED;
import static com.obimon.obimon_mobile.MyActivity.context;

/**
 * Created by av on 2016.11.06..
 */
public class ManageFragment extends Fragment {

    private final String TAG = "ManageFragment";

    int MAXNAME = 6;

    MyActivity myActivity;

    TextView labelName;
    Button buttonName;

    TextView labelMemory;
    TextView labelSync;
    //TextView labelAPIversion;
    CharSequence[] roles = {"Standalone", "Transmits sync to others", "Listens for sync", "Test mode (simulate data)"};


    TextView labelObimonVersion;
    TextView labelLatestVersion;
    TextView labelBtVersion;


    Button buttonReadMem;
    Button buttonErase;
    Button buttonUpgrade;

    void setLabels() {

        final ManageObimon manageObimon = MyActivity.myTestService.manageObimon;

        myActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {

                if(manageObimon.sendEmail) {
                    manageObimon.sendEmail=false;
                    startActivity(Intent.createChooser(manageObimon.emailIntent, "Your email id"));
                }


                boolean e = false;
                if (manageObimon.state == IDLE &&
                        manageObimon.name != null) e = true;

                //Log.d(TAG, "stop "+stop+" buttonReadMem "+buttonReadMem);

                buttonReadMem.setEnabled(e);
                buttonErase.setEnabled(e);
                buttonName.setEnabled(e);

                //if(MyActivity.myTestService.manageObimon.role == -1) spinnerRole.setEnabled(false);
                //else spinnerRole.setEnabled(e);

                if (manageObimon.state == UNCONNECTED) {
                    labelName.setText("-");
                    labelBtVersion.setText("-");

                    labelMemory.setText("-");
                    labelBtVersion.setText("-");
                    //labelAPIversion.setText("-");

                    labelObimonVersion.setText("-");
                } else {
                    labelName.setText(manageObimon.name);

                    double freeMem = 100 - (int) ((1000 * manageObimon.memptr - 65536) / (8 * 1024 * 1024 - 65536)) / 10;
                    labelMemory.setText("" + freeMem + "%");
                    //labelSync.setText("" + manageObimon.sync + "ms");

                    labelBtVersion.setText(""+manageObimon.btversion);

                    //labelAPIversion.setText("" + MyActivity.myTestService.manageObimon.apiversion);

                    if(manageObimon.build!=null)
                        labelObimonVersion.setText(manageObimon.build);
                    else labelObimonVersion.setText("wait...");

                }

                //if(MyActivity.myTestService.manageObimon.build==null ||
                buttonUpgrade.setEnabled(e);

                if (manageObimon.state == READMEM) {
                    buttonReadMem.setText("Reading..." + manageObimon.progress + "%");
                } else {
                    buttonReadMem.setText("Read memory");
                }

                if (manageObimon.state == ERASE) {
                    buttonErase.setText("Erasing..." + manageObimon.progress + "%");
                } else {
                    buttonErase.setText("Erase memory");
                }

                if (MyActivity.myTestService.programmingInProgress) {
                    buttonUpgrade.setText("Upgrading "+MyActivity.myTestService.programmingPercentage+"%");
                } else if (MyActivity.myTestService.resetAfterProgramming) {
                    buttonUpgrade.setText("Rebooting obimon...");
                } else {
                    buttonUpgrade.setText("Upgrade device");
                }
            }
        });
    }

    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            // intent can contain anydata
            if (intent.getAction().equals(ManageObimon.BROADCAST_REFRESH)) {
                Log.d(TAG, "BROADCAST_REFRESH");

                final ManageObimon manageObimon = MyActivity.myTestService.manageObimon;

                myActivity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {

                        if(manageObimon.sendEmail) {
                            manageObimon.sendEmail=false;
                            startActivity(Intent.createChooser(manageObimon.emailIntent, "Your email id"));
                        }


                        boolean e = false;
                        if (manageObimon.state == IDLE &&
                                manageObimon.name != null) e = true;

                        //Log.d(TAG, "stop "+stop+" buttonReadMem "+buttonReadMem);

                        buttonReadMem.setEnabled(e);
                        buttonErase.setEnabled(e);
                        buttonName.setEnabled(e);

                        //if(MyActivity.myTestService.manageObimon.role == -1) spinnerRole.setEnabled(false);
                        //else spinnerRole.setEnabled(e);

                        if (manageObimon.state == UNCONNECTED) {
                            labelName.setText("-");
                            labelBtVersion.setText("-");

                            labelMemory.setText("-");
                            //labelAPIversion.setText("-");

                            labelObimonVersion.setText("-");
                        } else {
                            labelName.setText(manageObimon.name);

                            double freeMem = 100 - (int) ((1000 * manageObimon.memptr - 65536) / (8 * 1024 * 1024 - 65536)) / 10;
                            labelMemory.setText("" + freeMem + "%");
                            //labelSync.setText("" + manageObimon.sync + "ms");

                            labelBtVersion.setText(""+manageObimon.btversion);

                            //labelAPIversion.setText("" + MyActivity.myTestService.manageObimon.apiversion);

                            if(manageObimon.build!=null)
                                labelObimonVersion.setText(manageObimon.build);
                            else labelObimonVersion.setText("Very old...");

                        }

                        //if(MyActivity.myTestService.manageObimon.build==null ||
                        buttonUpgrade.setEnabled(e);

                        if (manageObimon.state == READMEM) {
                            buttonReadMem.setText("Reading..." + manageObimon.progress + "%");
                        } else {
                            buttonReadMem.setText("Read memory");
                        }

                        if (manageObimon.state == ERASE) {
                            buttonErase.setText("Erasing..." + manageObimon.progress + "%");
                        } else {
                            buttonErase.setText("Erase memory");
                        }

                        if (MyActivity.myTestService.programmingInProgress) {
                            buttonUpgrade.setText("Upgrading "+MyActivity.myTestService.programmingPercentage+"%");
                        } else if (MyActivity.myTestService.resetAfterProgramming) {
                            buttonUpgrade.setText("Rebooting obimon...");
                        } else {
                            buttonUpgrade.setText("Upgrade device");
                        }
                    }
                });

            }
        }
    };



//    @Override
//    public void onAttach(Activity activity) {
//        super.onAttach(activity);
//
//        Log.i(TAG, "onAttach");
//
//        myActivity = (MyActivity) activity;
//        StartUpdateThread();
//
//    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        Log.i(TAG, "onAttach context ");

        myActivity = (MyActivity) getActivity();
        //StartUpdateThread(); // should not start thread here

    }



    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy");

    }

    @Override
    public void onDetach() {
        super.onDetach();
        Log.i(TAG, "onDetach");
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        Log.i(TAG, "onDestroyView");

    }

    @Override
    public void onPause() {
        super.onPause();
        Log.i(TAG, "onPause");

        LocalBroadcastManager.getInstance(getActivity()).unregisterReceiver(broadcastReceiver);

    }

    @Override
    public void onResume() {
        super.onResume();
        Log.i(TAG, "onResume");

        // register received in onResume and deregister in onPause
        IntentFilter intentFilter = new IntentFilter(ManageObimon.BROADCAST_REFRESH);
        LocalBroadcastManager.getInstance(getActivity()).registerReceiver(broadcastReceiver, intentFilter);

    }


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        Log.i(TAG, "onCreateView");

        View rootView = inflater.inflate(R.layout.fragment_section_manage, container, false);

        labelName = (TextView)rootView.findViewById(R.id.manage_name);

        labelObimonVersion = (TextView)rootView.findViewById(R.id.manage_obimon_version);
        labelLatestVersion = (TextView)rootView.findViewById(R.id.manage_latest_version);
        labelBtVersion = (TextView)rootView.findViewById(R.id.manage_obimon_btversion);


        labelMemory = (TextView)rootView.findViewById(R.id.manage_memory);
        //labelAPIversion = (TextView)rootView.findViewById(R.id.manage_apiversion);

        labelLatestVersion.setText(MyActivity.myTestService.latestFirmwareDate);
        labelObimonVersion.setText("");

        PackageInfo pInfo = null;
        try {
            pInfo = MyActivity.myTestService.getPackageManager().getPackageInfo(MyActivity.myTestService.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        String version = pInfo.versionName;

        TextView labelAppVersion = (TextView)rootView.findViewById(R.id.appVersion);
        labelAppVersion.setText(version);

//        List<String> list = new ArrayList<String>();
//        list.add("Standalone");
//        list.add("Transmits sync to others");
//        list.add("Listens for sync");
//        list.add("Test mode (simulate data)");
//        ArrayAdapter<String> dataAdapter = new ArrayAdapter<String>(getContext(), android.R.layout.simple_spinner_item, list);
//        dataAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
//        spinnerRole.setEnabled(false);
//        spinnerRole.setAdapter(dataAdapter);
//
//        spinnerRole.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
//            @Override
//            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
//                if(MyActivity.myTestService.manageObimon.role == -1) return;
//                if(MyActivity.myTestService.manageObimon.roleJustRead) {
//                    MyActivity.myTestService.manageObimon.roleJustRead = false;
//                    return;
//                }
//                Log.d("Manage", "Item selected "+position);
//                MyActivity.myTestService.manageObimon.role = position;
//                MyActivity.myTestService.manageObimon.state = CHANGE_ROLE;
//            }
//
//            @Override
//            public void onNothingSelected(AdapterView<?> parent) {
//
//            }
//        });

        buttonUpgrade = (Button)rootView.findViewById(R.id.manage_button_upgrade);
        buttonUpgrade.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d("BL", "upgrade button pressed");

                AlertDialog.Builder builder = new AlertDialog.Builder(ManageFragment.this.getContext());
                builder.setTitle("Please confirm Obimon firmware upgrade");

                // Set up the input
                //final EditText input = new EditText(ManageFragment.this.getContext());
                // Specify the type of input expected; this, for example, sets the input as a password, and will mask the text
                ///input.setInputType(InputType.TYPE_CLASS_TEXT);
                //builder.setView(input);

                // Set up the buttons
                builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Intent batteryIntent = getContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                        int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                        int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

                        float batteryPct = level / (float)scale;
                        if(batteryPct < 0.2) {
                            Toast.makeText(myActivity.getBaseContext(), "Battery too low for Obimon upgrade (min 20%)!", Toast.LENGTH_SHORT).show();
                            Log.d("BL", "battery level:" + level + " scale:" + scale);
                            return;
                        }

                        MyActivity.myTestService.programmingInProgress = true;
                        MyActivity.myTestService.programmingPercentage = 0;
                        MyActivity.myTestService.manageObimon.state = RESET;
                    }
                });
                builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.cancel();
                    }
                });

                builder.show();

            }
        });




        buttonName = (Button)rootView.findViewById(R.id.manage_button_name);
        buttonName.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d(TAG, "BUTTON NAME Clicked");

                AlertDialog.Builder builder = new AlertDialog.Builder(ManageFragment.this.getContext());
                builder.setTitle("New name");

                // Set up the input
                final EditText input = new EditText(ManageFragment.this.getContext());
                // Specify the type of input expected; this, for example, sets the input as a password, and will mask the text
                input.setInputType(InputType.TYPE_CLASS_TEXT);
                builder.setView(input);

                // Set up the buttons
                builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String s = input.getText().toString();
                        if(s.length()==0 || s.length() > MAXNAME) return;
                        //labelName.setText(input.getText().toString());
                        MyActivity.myTestService.manageObimon.name = s;
                        MyActivity.myTestService.manageObimon.state = CHANGE_NAME;
                    }
                });
                builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.cancel();
                    }
                });

                builder.show();

            }
        });



        buttonReadMem = (Button) rootView.findViewById(R.id.manage_button_load);
        buttonReadMem.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.d(TAG,"Clicked read mem");
                MyActivity.myTestService.manageObimon.state = READMEM;
            }
        });

        buttonErase = (Button) rootView.findViewById(R.id.manage_button_erase);
        buttonErase.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.d(TAG,"Clicked erase");

                AlertDialog.Builder builder = new AlertDialog.Builder(ManageFragment.this.getContext());
                builder.setTitle("Erase Obimon memory?");

                // Set up the input
                //final EditText input = new EditText(ManageFragment.this.getContext());
                // Specify the type of input expected; this, for example, sets the input as a password, and will mask the text
                ///input.setInputType(InputType.TYPE_CLASS_TEXT);
                //builder.setView(input);

                // Set up the buttons
                builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        MyActivity.myTestService.manageObimon.state = ERASE;
                    }
                });
                builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.cancel();
                    }
                });

                builder.show();

            }
        });

        setLabels();

        return rootView;
    }




}
