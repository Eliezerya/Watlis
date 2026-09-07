package com.watlis.app.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import java.util.List;

@Dao
public interface StoryDao {
    @Query("SELECT * FROM story_memory WHERE mediaId = :mediaId LIMIT 1") StoryMemoryEntity get(long mediaId);
    @Insert(onConflict = OnConflictStrategy.REPLACE) void save(StoryMemoryEntity memory);
    @Query("SELECT * FROM characters WHERE mediaId = :mediaId ORDER BY id") List<CharacterEntity> characters(long mediaId);
    @Insert long addCharacter(CharacterEntity character);
    @Update void updateCharacter(CharacterEntity character);
    @Query("DELETE FROM characters WHERE id = :id") void deleteCharacter(long id);
}
