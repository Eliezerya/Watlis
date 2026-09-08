package com.watlis.app.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface ProgressDao {
    @Query("SELECT * FROM user_progress") java.util.List<UserProgressEntity> getAll();
    @Query("SELECT * FROM user_progress WHERE mediaId = :mediaId LIMIT 1") UserProgressEntity get(long mediaId);
    @Insert(onConflict = OnConflictStrategy.REPLACE) void save(UserProgressEntity progress);
    @Query("SELECT COUNT(*) FROM user_progress WHERE trackingStatus = :status") int countStatus(String status);
    @Query("SELECT AVG(rating) FROM user_progress WHERE rating IS NOT NULL") Double averageRating();
}
