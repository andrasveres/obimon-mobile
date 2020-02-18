package com.obimon.obimon_mobile;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.support.v4.app.Fragment;
import android.text.Html;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Created by andrasveres on 03/04/15.
 */
public class SelectFragment extends Fragment {
    private final String TAG = "SelectFragment";

    MyActivity myActivity;
    TextView labelNumAll, labelNumMyGroup;

    public ArrayList<ObimonGroupItem> groupDevices = new ArrayList<>();
    ArrayAdapter itemsAdapter;

    UpdateThread updateThread=null;

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        Log.i(TAG, "onAttach");

        myActivity = (MyActivity) activity;
    }

    class UpdateThread extends Thread {
        boolean stop = false;

        @Override
        public void run() {
            Log.i(TAG, "Start UpdateThread");

            while(true) {

                if(stop) {
                    Log.i(TAG, "Stop UpdateThread");
                    break;
                }


                myActivity.runOnUiThread(new Runnable() {

                    @Override
                    public void run() {

                        if(MyActivity.myTestService==null) return;

                        HashSet<ObimonDevice> deviceSet = new HashSet<ObimonDevice>();
                        // add new devices to list
                        for(ObimonDevice obimon : MyActivity.myTestService.foundDevices.values()) {

                            if(obimon.group.compareTo(MyActivity.group)!=0 && MyActivity.group.compareTo("xxxx")!=0) {
                                if(System.currentTimeMillis() - obimon.lastTsBroadcast > 60000) {
                                    obimon.selected = false;
                                    continue;
                                }
                            }

                            deviceSet.add(obimon);

                            boolean found = false;
                            for(ObimonGroupItem item : groupDevices) {
                                if(item.obimon == obimon) {
                                    found = true;
                                    break;
                                }
                            }

                            if(!found) {
                                ObimonGroupItem newItem = new ObimonGroupItem();
                                newItem.obimon = obimon;

                                SharedPreferences savedSelection = myActivity.getSharedPreferences("selected", 0);
                                obimon.selected = savedSelection.getBoolean(obimon.addr, false);

                                Log.v(TAG, "new list item "+obimon.addr+" "+obimon.selected);

                                groupDevices.add(newItem);

                            }

                        }

                        // update items
                        Iterator<ObimonGroupItem> it = groupDevices.iterator();
                        while(it.hasNext()) {
                            ObimonGroupItem item = it.next();
                            if(!deviceSet.contains(item.obimon)) {
                                it.remove();
                            }
                        }

                        Collections.sort(groupDevices, new CustomComparator());

                        int i=0;
                        for(ObimonGroupItem item : groupDevices) {
                            ObimonDevice obimon = item.obimon;

                            obimon.color = ObiColors.colors[i % ObiColors.colors.length];
                            i++;

                        }

                        itemsAdapter.notifyDataSetChanged();

                        labelNumAll.setText(""+MyActivity.myTestService.foundDevices.size());
                        labelNumMyGroup.setText(""+groupDevices.size());

                    }
                });

                try {
                    sleep(100);
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
        if(updateThread==null) {
            updateThread = new UpdateThread();
            updateThread.start();
        }
    }

    void StopUpdateThread() {
        if(updateThread!=null) updateThread.stop=true;
        updateThread=null;
    }


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        Log.i(TAG, "onCreateView");

        View rootView = inflater.inflate(R.layout.fragment_section_selection, container, false);

        labelNumAll = (TextView)rootView.findViewById(R.id.numAll);
        labelNumMyGroup = (TextView)rootView.findViewById(R.id.numMyGroup);


        itemsAdapter = new ObimonSelectionAdapter(getActivity(), groupDevices);

        ListView listView = (ListView) rootView.findViewById(R.id.selectListView);
        listView.setAdapter(itemsAdapter);

        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        //list.setItemChecked(0, true);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                ObimonGroupItem item = groupDevices.get(position);
                item.obimon.selected = !item.obimon.selected;

                SharedPreferences savedSelection = myActivity.getSharedPreferences("selected", 0);
                SharedPreferences.Editor editor = savedSelection.edit();
                editor.putBoolean(item.obimon.addr, item.obimon.selected);
                editor.commit();

                //sclWindow = settings.getInt("scl_window", 60);

                if(item.obimon.selected) view.setBackgroundColor(Color.LTGRAY);
                else view.setBackgroundColor(Color.WHITE);
            }
        });

        //getListView.setAdapter(new ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, listItems));
        //getListView().setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);

        StartUpdateThread();

        return rootView;
    }

    public class CustomComparator implements Comparator<ObimonGroupItem> {
        @Override
        public int compare(ObimonGroupItem o1, ObimonGroupItem o2) {
            return o1.obimon.name.compareTo(o2.obimon.name);
        }
    }

    class ObimonGroupItem {
        ObimonDevice obimon=null;
    }

    public class ObimonSelectionAdapter extends ArrayAdapter<ObimonGroupItem> {

        public ObimonSelectionAdapter(Context context, ArrayList<ObimonGroupItem> items) {

            super(context, 0, items);

        }



        @Override

        public View getView(int position, View convertView, ViewGroup parent) {

            // Get the data item for this position

            ObimonGroupItem item = getItem(position);

            // Check if an existing view is being reused, otherwise inflate the view

            if (convertView == null) {

                convertView = LayoutInflater.from(getContext()).inflate(R.layout.obimon_selection_item, parent, false);

            }

            // Lookup view for data population

            TextView mac = (TextView) convertView.findViewById(R.id.obimon_selection_name);
            TextView name = (TextView) convertView.findViewById(R.id.text);
            TextView status = (TextView) convertView.findViewById(R.id.status);

            // Populate the data into the template view using the data object

            //Log.d(TAG, "Update select fragment obi labels");
            name.setText(item.obimon.name);

            String s="";
            if(System.currentTimeMillis() - item.obimon.lastSeen > 60000) {
                s = "LOST";
            } else if(System.currentTimeMillis() - item.obimon.lastGsrTime <10000) {
                //s = "MEASURING";
                s = String.format("%.2f uS", item.obimon.lastGsr);
            }

            mac.setText(Html.fromHtml(item.obimon.addr.substring(9)));

            String extra="";
//            if(item.obimon.build.compareTo("unknown")!=0) {
//                if(item.obimon.build.compareTo(MyTestService.latestFirmwareDate)!=0) {
//                    //Log.d(TAG, "Old firmware");
//                    extra += "<font color='#EE0000'>F</font>";
//                }
//            }

            if(item.obimon.lastSessionSync>0) {
                extra += " <font color='#008000'>SYNC</font>";
            } else {
                extra += " <font color='#EE0000'>SYNC</font>";
            }


            String battext="";
            if(item.obimon.bat==0) battext += "-V ";
            else battext+=item.obimon.bat+"V ";
            if(item.obimon.bat<=3.3) {
                battext = "<font color='#AA0000'>"+battext+"</font>";
            }
            status.setText(Html.fromHtml(battext+(100-item.obimon.mem)+"% "+extra));

            // Return the completed view to render on screen

            if(item.obimon.selected) convertView.setBackgroundColor(Color.LTGRAY);
            else convertView.setBackgroundColor(Color.WHITE);

            return convertView;

        }

    }


}
