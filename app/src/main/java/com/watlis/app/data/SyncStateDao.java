package com.watlis.app.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface SyncStateDao {
    @Query("SELECT * FROM sync_state ORDER BY `key`") List<SyncStateEntity> all();
    @Insert(onConflict = OnConflictStrategy.REPLACE) void save(SyncStateEntity state);
    @Query("DELETE FROM sync_state") void clear();
}
