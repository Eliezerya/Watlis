package com.watlis.app.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface ProgressHistoryDao {
    @Insert long insert(ProgressHistoryEntity change);
    @Query("SELECT * FROM progress_history WHERE mediaId = :mediaId ORDER BY id DESC LIMIT 1")
    ProgressHistoryEntity latest(long mediaId);
    @Query("SELECT * FROM progress_history WHERE mediaId = :mediaId AND id < :beforeId ORDER BY id DESC LIMIT :limit")
    List<ProgressHistoryEntity> page(long mediaId, long beforeId, int limit);
    @Query("SELECT * FROM progress_history WHERE mediaId = :mediaId ORDER BY id")
    List<ProgressHistoryEntity> forBackup(long mediaId);
}
