package com.obimon.obimon_mobile;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;

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

