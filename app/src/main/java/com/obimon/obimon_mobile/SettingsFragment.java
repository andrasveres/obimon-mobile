package com.obimon.obimon_mobile;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Date;

import static com.obimon.obimon_mobile.MyTestService.BROADCAST_TIMECHANGED;

/**
 * Created by av on 2015.12.28..
 */
public class SettingsFragment extends Fragment {
    String TAG = "SettingsFragment";

    TextView valueSclWindow;
    TextView valueGraphHistory;
    TextView ntpstatus;

    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            // intent can contain anydata
            if (intent.getAction().equals(BROADCAST_TIMECHANGED)) {
                //Log.d(TAG, "BROADCAST_TIMECHANGED");

                setNtpStatus();
            }
        }
    };

    void setNtpStatus() {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {

                Date date = new Date(System.currentTimeMillis() + MyActivity.myTestService.ntpCorr);

                if(MyActivity.myTestService.ntp) {
                    ntpstatus.setText("Network time OK "+date);
                    ntpstatus.setBackgroundColor(Color.WHITE);
                } else {
                    ntpstatus.setText("Network time not available, using phone time "+date);
                    ntpstatus.setBackgroundColor(Color.RED);
                }
            }
        });

    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_section_settings, container, false);
        //Bundle args = getArguments();
        //((TextView) rootView.findViewById(android.R.id.text1)).setText(
        //        getString(R.string.dummy_section_text, args.getInt(ARG_SECTION_NUMBER)));

        ntpstatus = (TextView) rootView.findViewById(R.id.ntpstatus);
        setNtpStatus();

        Switch sw = (Switch)rootView.findViewById(R.id.adjust_scl);

        sw.setChecked(MyActivity.adjustScl);

        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Log.i(TAG, "Switch " + isChecked);

                MyActivity.adjustScl = isChecked;

                return;
            }
        });

        final Button buttonSclWindow = (Button) rootView.findViewById(R.id.button_scl_window);
        valueSclWindow = (TextView) rootView.findViewById(R.id.value_scl_window);
        buttonSclWindow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showInputDialog(buttonSclWindow, valueSclWindow);
            }
        });

        final Button buttonGraphHistory = (Button) rootView.findViewById(R.id.button_graph_history);
        valueGraphHistory = (TextView) rootView.findViewById(R.id.value_graph_history);
        buttonGraphHistory.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showInputDialog(buttonGraphHistory, valueGraphHistory);
            }
        });


        valueSclWindow.setText("" + MyActivity.sclWindow);
        valueGraphHistory.setText("" + MyActivity.graphHistory);

        return rootView;
    }

    void saveSettings() {
        SharedPreferences settings = this.getActivity().getSharedPreferences("settings", 0);
        SharedPreferences.Editor editor = settings.edit();
        editor.putInt("scl_window", MyActivity.sclWindow);
        editor.putInt("graph_history", MyActivity.graphHistory);
        editor.putString("group", MyActivity.group);
        editor.commit();
    }


    protected void showInputDialog(Button b, final TextView v) {

        // get prompts.xml view
        final LayoutInflater layoutInflater = LayoutInflater.from(this.getContext());
        View promptView = layoutInflater.inflate(R.layout.input_dialog, null);
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(getContext());
        alertDialogBuilder.setView(promptView);

        final EditText editText = (EditText) promptView.findViewById(R.id.edittext);
        final TextView label = (TextView) promptView.findViewById(R.id.textView);

        if(v == valueSclWindow) label.setText("Set SCL window");
        if(v == valueGraphHistory) label.setText("Set graph history");

        // setup a dialog window
        alertDialogBuilder.setCancelable(false)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {

                        v.setText(editText.getText());

                        if (v == valueGraphHistory) {
                            int newValue = Integer.parseInt(String.valueOf(v.getText()));
                            if(newValue > 1) MyActivity.graphHistory = newValue;
                            saveSettings();
                        }

                        if (v == valueSclWindow) {
                            int newValue = Integer.parseInt(String.valueOf(v.getText()));
                            if(newValue>1) MyActivity.sclWindow = Integer.parseInt(String.valueOf(v.getText()));
                            saveSettings();

                        }

                    }
                })
                .setNegativeButton("Cancel",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                dialog.cancel();
                            }
                        });

        // create an alert dialog
        AlertDialog alert = alertDialogBuilder.create();
        alert.show();
    }

    @Override
    public void onResume() {
        super.onResume();

        // register received in onResume and deregister in onPause
        IntentFilter intentFilter = new IntentFilter(BROADCAST_TIMECHANGED);
        LocalBroadcastManager.getInstance(getActivity()).registerReceiver(broadcastReceiver, intentFilter);

    }

    @Override
    public void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(getActivity()).unregisterReceiver(broadcastReceiver);

    }

}