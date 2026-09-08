package com.watlis.app.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;
import java.util.List;

@Dao
public interface MediaTypeDao {
    @Query("SELECT * FROM media_types ORDER BY name COLLATE NOCASE") List<MediaTypeEntity> getAll();
    @Query("SELECT * FROM media_types WHERE `key` = :key") MediaTypeEntity get(String key);
    @Query("SELECT * FROM media_types WHERE name = :name COLLATE NOCASE LIMIT 1") MediaTypeEntity findByName(String name);
    @Insert void insert(MediaTypeEntity type);
    @Update void update(MediaTypeEntity type);
    @Query("DELETE FROM media_types WHERE `key` = :key") void delete(String key);
    @Query("UPDATE media SET type = :replacement, updatedAt = :now WHERE type = :key")
    void reassign(String key, String replacement, long now);
    @Query("SELECT COUNT(*) FROM media WHERE type = :key") int usage(String key);
}
