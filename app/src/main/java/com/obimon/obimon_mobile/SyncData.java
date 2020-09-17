package com.obimon.obimon_mobile;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

@Entity(tableName = "sessionsync")
public class SyncData {

    @PrimaryKey(autoGenerate = true)
    private int id;

    public int getId() {
        return id;
    }

    public long getMobilets() {
        return mobilets;
    }

    public String getDevice() {
        return device;
    }


    public long getSession() {
        return session;
    }

    public long getTs() {
        return ts;
    }

    public long getOffset() {
        return offset;
    }

    public long getNtpcorr() {
        return ntpcorr;
    }

    @ColumnInfo(name="mobilets")
    private long mobilets;

    @ColumnInfo(name="device")
    private String device;

    @ColumnInfo(name="session")
    private long session;

    @ColumnInfo(name="ts")
    private long ts;

    @ColumnInfo(name="offset")
    private long offset;

    @ColumnInfo(name="ntpcorr")
    private long ntpcorr;


    public SyncData(int id, long mobilets, String device, long session, long ts, long offset, long ntpcorr) {
        this.id = id;
        this.device = device;
        this.mobilets = mobilets;
        this.session = session;
        this.ts = ts;
        this.offset = offset;
        this.ntpcorr = ntpcorr;
    }

    @Ignore
    public SyncData(long mobilets, String device, long session, long ts, long offset, long ntpcorr) {
        this.session = session;
        this.device = device;
        this.mobilets = mobilets;
        this.ts = ts;
        this.offset = offset;
        this.ntpcorr = ntpcorr;
    }

}
