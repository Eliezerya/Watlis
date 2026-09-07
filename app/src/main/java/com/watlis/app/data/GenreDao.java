package com.watlis.app.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface GenreDao {
    @Query("SELECT * FROM genres ORDER BY name COLLATE NOCASE") List<GenreEntity> getAll();
    @Query("SELECT * FROM genres WHERE id = :id LIMIT 1") GenreEntity get(long id);
    @Query("SELECT * FROM genres WHERE LOWER(name) = LOWER(:name) LIMIT 1") GenreEntity findByName(String name);
    @Query("SELECT g.* FROM genres g INNER JOIN media_genres mg ON g.id = mg.genreId WHERE mg.mediaId = :mediaId ORDER BY g.name COLLATE NOCASE") List<GenreEntity> forMedia(long mediaId);
    @Insert(onConflict = OnConflictStrategy.ABORT) long insert(GenreEntity genre);
    @Update void update(GenreEntity genre);
    @Query("DELETE FROM genres WHERE id = :id") void delete(long id);
    @Query("DELETE FROM media_genres WHERE mediaId = :mediaId") void clearForMedia(long mediaId);
    @Insert(onConflict = OnConflictStrategy.REPLACE) void addToMedia(MediaGenreCrossRef ref);
}
