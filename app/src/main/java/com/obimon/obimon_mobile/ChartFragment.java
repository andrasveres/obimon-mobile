package com.obimon.obimon_mobile;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import org.achartengine.ChartFactory;
import org.achartengine.GraphicalView;
import org.achartengine.chart.PointStyle;
import org.achartengine.model.XYMultipleSeriesDataset;
import org.achartengine.model.XYSeries;
import org.achartengine.renderer.XYMultipleSeriesRenderer;
import org.achartengine.renderer.XYSeriesRenderer;

import java.util.HashMap;

/**
 * Created by andrasveres on 27/03/15.
 */
public class ChartFragment extends Fragment {
    String TAG = "ChartFragment";

    MyActivity activity;
    public GraphicalView chartView=null;
    public XYMultipleSeriesRenderer mRenderer=null;
    public XYMultipleSeriesDataset dataset=null;

    ChartThread chartThread =null;

    void CreateRenderer() {

        if(mRenderer!=null) return;

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float val = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12, metrics);

        mRenderer = new XYMultipleSeriesRenderer();
        //mRenderer.setChartTitle("Skin conductance");
        //mRenderer.setXTitle("Seconds");
        //mRenderer.setYTitle("uSiemens");
        mRenderer.setZoomButtonsVisible(false);
        mRenderer.setAxisTitleTextSize(val);
        mRenderer.setLabelsTextSize(val);
        mRenderer.setAntialiasing(true);
        //mRenderer.setXLabels(maxn); // display maxn points
        mRenderer.setYLabelsAlign(Paint.Align.RIGHT);
        //mRenderer.setYLabelsPadding(-50);
        mRenderer.setMargins(new int[]{0, (int) val * 4, (int) val * 2, 0});
        mRenderer.setShowAxes(true);
        mRenderer.setShowGrid(true); // we show the grid

        mRenderer.setLegendTextSize(val);
        //mRenderer.setLegendHeight(500);
        mRenderer.setFitLegend(true);
        mRenderer.setShowLegend(true);

        mRenderer.setPanEnabled(false, false);
        mRenderer.setClickEnabled(false);
        mRenderer.setZoomEnabled(false, false);
        mRenderer.setExternalZoomEnabled(false);

        mRenderer.setAxesColor(Color.DKGRAY);
        mRenderer.setBackgroundColor(Color.WHITE);
        mRenderer.setMarginsColor(Color.WHITE);
        mRenderer.setGridColor(Color.LTGRAY);
        mRenderer.setXLabelsColor(Color.DKGRAY);
        mRenderer.setApplyBackgroundColor(true);

        mRenderer.setPointSize(10);

        //DecimalFormat yformat = new DecimalFormat("###,###.000");
        //mRenderer.setYLabelFormat(yformat,0);

        mRenderer.setYLabelsColor(0, Color.DKGRAY);
    }

    void CreateDataset() {
        if(dataset==null) dataset = new XYMultipleSeriesDataset();
    }

    HashMap<ObimonDevice, XYSeriesRenderer> obimonChartDataMap = new HashMap<>();

    class ChartThread extends Thread {
        boolean trun =true;

        public void run() {
            super.run();

            Log.i(TAG, "ChartThread run");

            while(trun) {
                //Log.i("XXXX", "Repaint");

                if(MyActivity.myTestService == null) break;

                for(ObimonDevice obi: MyActivity.myTestService.foundDevices.values()) {
                    if(obimonChartDataMap.containsKey(obi)) {

                        if(!obi.selected) {
                            dataset.removeSeries(obi.series);
                            mRenderer.removeSeriesRenderer(obimonChartDataMap.get(obi));
                            obimonChartDataMap.remove(obi);
                            continue;
                        }

                        continue;
                    }

                    if(!obi.selected) continue;

                    XYSeriesRenderer renderer = new XYSeriesRenderer();;
                    renderer.setColor(obi.color);
                    renderer.setLineWidth(6);

                    //renderer.setPointStyle(PointStyle.TRIANGLE);
                    //renderer.setDisplayBoundingPoints(false);
                    //renderer.setFillPoints(true);

                    obimonChartDataMap.put(obi, renderer);

                    dataset.addSeries(obi.series);
                    mRenderer.addSeriesRenderer(renderer);
                }

                for(ObimonDevice obi : obimonChartDataMap.keySet()) {
                    XYSeriesRenderer renderer = obimonChartDataMap.get(obi);
                    renderer.setColor(obi.color);
                }

                // rescale
                double min=100;
                double max=-100;
                for(XYSeries series : dataset.getSeries()) {
                    double m = series.getMaxY();
                    if(m>max) max = m;

                    m = series.getMinY();
                    if(m<min) min = m;
                }

                if(max<0.1) max = 0.1;

                double range = Math.abs(max-min);

                mRenderer.setYAxisMin(min-range*0.1);
                mRenderer.setYAxisMax(max+range*0.1);

                chartView.repaint();

                try {
                    sleep(200);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

            }

            Log.i(TAG,"ChartThread exit");
        }
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        Log.i(TAG, "onAttach");
        this.activity = (MyActivity) activity;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_section_chart, container, false);

        Log.i(TAG, "onCreate");

        CreateRenderer();
        CreateDataset();


        return rootView;
    }

    @Override
    public void onResume() {
        super.onResume();

        Log.i(TAG, "onResume");

        //if(chartView==null) {
            drawChart();
        //} else chartView.repaint();


        if(chartThread == null) {
            chartThread = new ChartThread();
            chartThread.start();
        }


    }

    @Override
    public void onPause() {
        super.onPause();

        Log.i(TAG, "onPause");

        if(chartThread !=null) {
            chartThread.trun=false;
            chartThread = null;
        }
    }

    void drawChart() {
        Log.i(TAG, "drawChart dataset:"+dataset.getSeriesCount());


        //renderer.setDisplayBoundingPoints(true);
        //renderer.setPointStyle(PointStyle.CIRCLE);
        //renderer.setPointStrokeWidth(3);
        //mRenderer.addSeriesRenderer(renderer);

        // We want to avoid black border
        //mRenderer.setMarginsColor(Color.argb(0x00, 0xff, 0x00, 0x00)); // transparent margins

        //mRenderer.setXLabelsPadding(100);
        //chartView.setHorizontalScrollBarEnabled(true);
        //chartView = ChartFactory.getLineChartView(getActivity(), activity.dataset, activity.mRenderer);
        chartView = ChartFactory.getTimeChartView(getActivity(), dataset, mRenderer, "H:mm:ss");

        LinearLayout l = (LinearLayout) getActivity().findViewById(R.id.chart);

        //l.addView(chartView, new LinearLayout.LayoutParams(
        //        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));


        l.addView(chartView, 0);

    }


}