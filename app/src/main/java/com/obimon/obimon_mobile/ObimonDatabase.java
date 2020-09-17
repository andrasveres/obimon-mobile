package com.obimon.obimon_mobile;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import android.content.Context;

@Database(entities = SyncData.class, exportSchema = false, version = 1)
public abstract class ObimonDatabase extends RoomDatabase {
    private static final String DB_NAME = "obimon_db";
    private static ObimonDatabase instance;

    public static synchronized ObimonDatabase getInstance(Context context) {
        if(instance == null) {
            instance = Room.databaseBuilder(context.getApplicationContext(), ObimonDatabase.class, DB_NAME)
                    .fallbackToDestructiveMigration()
                    .allowMainThreadQueries()
                    .build();
        }

        return instance;
    }

    public abstract SyncDataDao syncDataDao();

}
