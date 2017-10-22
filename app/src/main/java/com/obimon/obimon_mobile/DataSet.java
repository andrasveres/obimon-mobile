package com.obimon.obimon_mobile;

import java.util.ArrayList;

/**
 * Created by andrasveres on 06/04/15.
 */
public class DataSet {

    int memPtr=0;
    long t=0;

    ArrayList<Data> data = new ArrayList<>();

    public class Data {
        long t;
        int gsr;

        Data(long t, int gsr) {
            this.t = t;
            this.gsr = gsr;
        }
    }

    void setTime(long t) {
        this.t = t;
    }

    void addGsr(int gsr) {
        Data d = new Data(t, gsr);
        data.add(d);
        t += 125;
    }
}
