package com.obimon.obimon_mobile;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseExpandableListAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ExpandableListView;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Created by andrasveres on 03/04/15.
 */
public class DevicesFragment extends Fragment {
    private final String TAG = "DevicesFragment";

    MyActivity myActivity;

    public ArrayList<ObimonListItem> listedDevices = new ArrayList<>();

    MyExpandableListAdapter listAdapter;
    ExpandableListView expListView;
    HashMap<String, List<String>> listDataChild;

    UpdateThread updateThread=null;

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        Log.i(TAG, "DevicesFragment onAttach");

        myActivity = (MyActivity) activity;
    }

    class UpdateThread extends Thread {
        boolean stop = false;

        @Override
        public void run() {
            Log.i(TAG, "Start UpdateThread");
            stop = false;

            while(true) {

                if(stop) {
                    Log.i(TAG, "Stop UpdateThread "+stop);
                    break;
                }

                if(MyActivity.myTestService==null) return;

                Log.d(TAG, "UpdateThread ...");

                // add new devices to list
                for(ObimonDevice obimon : MyActivity.myTestService.foundDevices.values()) {

                    //if(!obimon.selected) continue;

                    boolean found = false;
                    for(ObimonListItem item : listedDevices) {
                        if(item.obimon == obimon) {
                            found = true;
                            break;
                        }
                    }

                    if(!found) {
                        ObimonListItem newItem = new ObimonListItem();
                        newItem.obimon = obimon;

                        SharedPreferences savedSelection = myActivity.getSharedPreferences("selected", 0);
                        obimon.selected = savedSelection.getBoolean(obimon.addr, false);

                        listedDevices.add(newItem);


                    }

                }

                //ArrayList<ObimonListItem> toDelete = new ArrayList<ObimonListItem>();

                // update items
                for(ObimonListItem item : listedDevices) {

//                            if(!item.obimon.selected) {
//                                toDelete.add(item);
//                                continue;
//                            }

                    item.received += item.obimon.received;
                    item.lost += item.obimon.lost;

                    item.obimon.received=0;
                    item.obimon.lost=0;

                    item.bat = item.obimon.bat;
                    item.mem = item.obimon.mem;
                    item.signal = item.obimon.signal;
                    item.lastSessionSync = item.obimon.lastSessionSync;
                }

                // remove deselected items

//                        for(ObimonListItem item : toDelete) {
//                            listedDevices.remove(item);
//                        }

                Collections.sort(listedDevices, new CustomComparator());

                myActivity.runOnUiThread(new Runnable() {

                    @Override
                    public void run() {

                        //Log.d(TAG, "call NotifyDatasetChanged");

                        listAdapter.notifyDataSetChanged();
                    }
                });

                try {
                    sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

            }
        }
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

        StopUpdateThread();
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.i(TAG, "onResume");

        StartUpdateThread();

    }

    void StartUpdateThread() {
        Log.d(TAG, "StartUpdateThread called "+updateThread);

        if(updateThread==null) {
            Log.d(TAG, "...create new UpdateThread ");

            updateThread = new UpdateThread();
            updateThread.start();
        }

    }

    void StopUpdateThread() {
        Log.d(TAG, "StopUpdateThread called");
        if(updateThread!=null) {
            Log.d(TAG, "...initiate stop ");
            updateThread.stop=true;
        }

        updateThread=null;
    }


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        Log.i(TAG, "onCreateView");

        View rootView = inflater.inflate(R.layout.fragment_section_devices, container, false);

        // get the listview
        expListView = (ExpandableListView) rootView.findViewById(R.id.deviceListView);
        listAdapter = new MyExpandableListAdapter(myActivity, listedDevices);

        // setting list adapter
        expListView.setAdapter(listAdapter);
        expListView.setGroupIndicator(null);

        //StartUpdateThread();

        return rootView;
    }

    public class CustomComparator implements Comparator<ObimonListItem> {
        @Override
        public int compare(ObimonListItem o1, ObimonListItem o2) {
            return o1.obimon.name.compareTo(o2.obimon.name);
        }
    }


    class ObimonListItem {
        ObimonDevice obimon=null;
        int received=0;
        int lost=0;
        int mem=0;
        int signal=0;
        double bat=0;
        long lastSessionSync=0;
        String status;
    }

    public class MyCheckedChangeListener implements CompoundButton.OnCheckedChangeListener {
        ObimonDevice obi;

        public MyCheckedChangeListener(ObimonDevice obi) {
            this.obi = obi;
        }

        @Override
        public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
            obi.selected = b;
            Log.i(TAG, "Obi checkbox "+obi.name+" selection:"+obi.selected);

            SharedPreferences savedSelection = myActivity.getSharedPreferences("selected", 0);
            SharedPreferences.Editor editor = savedSelection.edit();
            editor.putBoolean(obi.addr, obi.selected);
            editor.commit();

        }
    }

    public class MyExpandableListAdapter extends BaseExpandableListAdapter {
        private Context _context;
        private List<ObimonListItem> _listDataHeader; // header titles
        // child data in format of header title, child title
        private HashMap<String, List<String>> _listDataChild;

        public MyExpandableListAdapter(Context context, List<ObimonListItem> listDataHeader) {
            this._context = context;
            this._listDataHeader = listDataHeader;
        }

        @Override
        public Object getChild(int groupPosition, int childPosititon) {
            //return this._listDataChild.get(this._listDataHeader.get(groupPosition))
            //        .get(childPosititon);
            return _listDataHeader.get(groupPosition);
        }

        @Override
        public long getChildId(int groupPosition, int childPosition) {
            return childPosition;
        }

        @Override
        public View getChildView(int groupPosition, final int childPosition,
                                 boolean isLastChild, View convertView, ViewGroup parent) {

            if (convertView == null) {
                LayoutInflater infalInflater = (LayoutInflater) this._context
                        .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                convertView = infalInflater.inflate(R.layout.obimon_item, null);
            }

            ObimonListItem item = _listDataHeader.get(groupPosition);

            TextView stat_value = (TextView) convertView.findViewById(R.id.stat_value);
            //stat_value.setText(""+item.received+"/"+item.lost);
            stat_value.setText(""+item.received);

            TextView battery_value = (TextView) convertView.findViewById(R.id.battery_value);
            battery_value.setText(""+item.bat+"V");
            if(item.bat>3) {
                battery_value.setBackgroundColor(Color.TRANSPARENT);
            } else battery_value.setBackgroundColor(Color.RED);


            TextView mem_value = (TextView) convertView.findViewById(R.id.mem_value);

            /*
            long t = item.mem;
            long hour = TimeUnit.SECONDS.toHours(t);
            long min = TimeUnit.SECONDS.toMinutes(t) - (TimeUnit.SECONDS.toHours(t)* 60);
            long sec = TimeUnit.SECONDS.toSeconds(t) - (TimeUnit.SECONDS.toMinutes(t) *60);
            mem_value.setText(String.format("%dh:%02dm:%02ds", hour, min, sec));
*/
            /*
            if(t<600) mem_value.setBackgroundColor(Color.RED);
            else if(t<-3600) mem_value.setBackgroundColor(Color.YELLOW);
            else mem_value.setBackgroundColor(Color.TRANSPARENT);
*/

            mem_value.setText(""+(100-item.mem)+"%");
            if(item.mem>90) mem_value.setBackgroundColor(Color.RED);
            else mem_value.setBackgroundColor(Color.TRANSPARENT);

            TextView signal_value = (TextView) convertView.findViewById(R.id.signal_value);
            signal_value.setText("" + item.signal + "dBm");

            TextView version_value = (TextView) convertView.findViewById(R.id.version);
            version_value.setText("" + item.obimon.build);

            TextView group_value = (TextView) convertView.findViewById(R.id.group);
            group_value.setText("" + item.obimon.group);

            if(item.signal<-90) signal_value.setBackgroundColor(Color.RED);
            else signal_value.setBackgroundColor(Color.TRANSPARENT);

            TextView sync_value = (TextView) convertView.findViewById(R.id.sync_value);
            if(item.lastSessionSync>0) {
                sync_value.setText("OK");
                sync_value.setBackgroundColor(Color.GREEN);
            } else {
                sync_value.setText("WAIT");
                sync_value.setBackgroundColor(Color.RED);
            }

            TextView mac_value = (TextView) convertView.findViewById(R.id.mac_value);
            mac_value.setText("" + item.obimon.addr.substring(9));

            return convertView;
        }

        @Override
        public int getChildrenCount(int groupPosition) {
            return 1; // we have exactly 1 item
            //return this._listDataChild.get(this._listDataHeader.get(groupPosition))
            //        .size();
        }

        @Override
        public ObimonListItem getGroup(int groupPosition) {
            return this._listDataHeader.get(groupPosition);
        }

        @Override
        public int getGroupCount() {
            return this._listDataHeader.size();
        }

        @Override
        public long getGroupId(int groupPosition) {
            return groupPosition;
        }

        @Override
        public View getGroupView(int groupPosition, boolean isExpanded,
                                 View convertView, ViewGroup parent) {
            if (convertView == null) {
                LayoutInflater infalInflater = (LayoutInflater) this._context
                        .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                convertView = infalInflater.inflate(R.layout.obimon_toplevel_item, null);
            }

            ObimonListItem item = _listDataHeader.get(groupPosition);

            TextView name = (TextView) convertView.findViewById(R.id.obimon_name);
            name.setText(item.obimon.name);

            if(item.bat <=3.4 && item.bat>0) {
                name.setBackgroundColor(Color.RED);
            } //else name.setBackgroundColor(Color.WHITE);

            TextView status = (TextView) convertView.findViewById(R.id.obimon_connection_status);

            String s="";
            if(System.currentTimeMillis() - item.obimon.lastSeen > 60000) {
                s = "LOST";
            } else if(System.currentTimeMillis() - item.obimon.lastGsrTime <10000) {
                //s = "MEASURING";
                s = String.format("%.2f uS", item.obimon.lastGsr);
            } else s="INRANGE";

            status.setText(s);

            TextView color_label = (TextView) convertView.findViewById(R.id.obimon_color_label);
            color_label.setText("\u2015");
            Typeface boldTypeface = Typeface.defaultFromStyle(Typeface.BOLD);
            color_label.setTypeface(boldTypeface);
            color_label.setTextColor(item.obimon.color);

            TextView sync_status = (TextView) convertView.findViewById(R.id.sync_status);
            if(item.lastSessionSync>0) {
                sync_status.setText("SYNC");
                sync_status.setBackgroundColor(Color.GREEN);
            } else {
                sync_status.setText("SYNC");
                sync_status.setBackgroundColor(Color.RED);
            }


            //Log.i(TAG, "Obi init checkbox "+item.obimon.name+" selection:"+item.obimon.selected);

            CheckBox obi_select = (CheckBox) convertView.findViewById(R.id.obimon_select);

            obi_select.setOnCheckedChangeListener(null);
            obi_select.setChecked(item.obimon.selected);
            obi_select.setOnCheckedChangeListener(new MyCheckedChangeListener(item.obimon));


            return convertView;
        }

        @Override
        public boolean hasStableIds() {
            return false;
        }

        @Override
        public boolean isChildSelectable(int groupPosition, int childPosition) {
            return true;
        }

    }

}
