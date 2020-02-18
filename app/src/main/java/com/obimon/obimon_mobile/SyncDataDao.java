package com.obimon.obimon_mobile;

import android.arch.persistence.room.Dao;
import android.arch.persistence.room.Delete;
import android.arch.persistence.room.Insert;
import android.arch.persistence.room.Query;

import com.obimon.obimon_mobile.SyncData;

import java.util.List;

@Dao
public interface SyncDataDao {
    @Query("Select * from sessionsync")
    List<SyncData> getSyncDataList();

    @Query("SELECT * FROM sessionsync where session = :sessionid")
    List<SyncData> getSyncDataList(long sessionid);

    @Insert
    void insertSyncData(SyncData s);

    @Delete
    void SyncData(SyncData s);
}

