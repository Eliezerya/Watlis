package com.watlis.app.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface MediaDao {
    @Query("SELECT * FROM media ORDER BY updatedAt DESC") List<MediaEntity> getAll();
    @Query("SELECT * FROM media WHERE id = :id LIMIT 1") MediaEntity getById(long id);
    @Insert long insert(MediaEntity media);
    @androidx.room.Update void update(MediaEntity media);
    @Delete void delete(MediaEntity media);
    @Query("SELECT COUNT(*) FROM media") int count();
    @Query("SELECT COUNT(*) FROM media WHERE isFavorite = 1") int favoriteCount();
}
