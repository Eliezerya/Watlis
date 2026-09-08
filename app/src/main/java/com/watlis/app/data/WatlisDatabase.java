package com.watlis.app.data;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {MediaEntity.class, GenreEntity.class, MediaGenreCrossRef.class,
        UserProgressEntity.class, StoryMemoryEntity.class, CharacterEntity.class, MediaTypeEntity.class}, version = 4, exportSchema = false)
public abstract class WatlisDatabase extends RoomDatabase {
    public abstract MediaDao mediaDao();
    public abstract ProgressDao progressDao();
    public abstract GenreDao genreDao();
    public abstract StoryDao storyDao();
    public abstract MediaTypeDao mediaTypeDao();

    private static volatile WatlisDatabase INSTANCE;
    public static WatlisDatabase get(Context context) {
        if (INSTANCE == null) {
            synchronized (WatlisDatabase.class) {
                if (INSTANCE == null) INSTANCE = Room.databaseBuilder(context.getApplicationContext(), WatlisDatabase.class, "watlis.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build();
            }
        }
        return INSTANCE;
    }

    public static final androidx.room.migration.Migration MIGRATION_3_4 =
            new androidx.room.migration.Migration(3, 4) {
        @Override public void migrate(@androidx.annotation.NonNull androidx.sqlite.db.SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS media_types (`key` TEXT NOT NULL PRIMARY KEY, name TEXT COLLATE NOCASE NOT NULL, usesEpisodes INTEGER NOT NULL)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_media_types_name ON media_types(name)");
            db.execSQL("INSERT INTO media_types VALUES ('manga','Manga',0),('manhwa','Manhwa',0),('manhua','Manhua',0),('anime','Anime',1)");
            db.execSQL("INSERT OR IGNORE INTO media_types SELECT DISTINCT type,type,0 FROM media WHERE type NOT IN (SELECT `key` FROM media_types)");
        }
    };

    public static final androidx.room.migration.Migration MIGRATION_2_3 =
            new androidx.room.migration.Migration(2, 3) {
        @Override public void migrate(@androidx.annotation.NonNull androidx.sqlite.db.SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE media ADD COLUMN coverPositionX REAL NOT NULL DEFAULT 0.5");
            db.execSQL("ALTER TABLE media ADD COLUMN coverPositionY REAL NOT NULL DEFAULT 0.5");
        }
    };

    public static final androidx.room.migration.Migration MIGRATION_1_2 =
            new androidx.room.migration.Migration(1, 2) {
        @Override public void migrate(@androidx.annotation.NonNull androidx.sqlite.db.SupportSQLiteDatabase db) {
            // Keep associations when consolidating names allowed by the original schema.
            db.execSQL("INSERT OR IGNORE INTO media_genres(mediaId,genreId) SELECT mg.mediaId,(SELECT MIN(g.id) FROM genres g WHERE lower(trim(g.name))=lower(trim(old.name))) FROM media_genres mg JOIN genres old ON old.id=mg.genreId");
            db.execSQL("DELETE FROM genres WHERE id NOT IN (SELECT MIN(id) FROM genres GROUP BY lower(trim(name)))");
            db.execSQL("UPDATE genres SET name=trim(name)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_genres_name ON genres(name COLLATE NOCASE)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_media_genres_genreId ON media_genres(genreId)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_characters_mediaId ON characters(mediaId)");
        }
    };
}
