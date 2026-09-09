package com.watlis.app;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.watlis.app.data.WatlisDatabase;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MigrationInstrumentedTest {
    @Test public void upgradePreservesMediaAndMergesDuplicateGenreLinks() {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name="watlis-migration-test-"+java.util.UUID.randomUUID()+".db";
        WatlisDatabase room=null;
        try {
            SQLiteDatabase old=context.openOrCreateDatabase(name,0,null);
            old.execSQL("CREATE TABLE media(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,title TEXT NOT NULL,type TEXT NOT NULL,coverImage TEXT,releaseStatus TEXT NOT NULL,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL,isFavorite INTEGER NOT NULL)");
            old.execSQL("CREATE TABLE genres(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,name TEXT NOT NULL,color TEXT NOT NULL)");
            old.execSQL("CREATE TABLE media_genres(mediaId INTEGER NOT NULL,genreId INTEGER NOT NULL,PRIMARY KEY(mediaId,genreId),FOREIGN KEY(mediaId) REFERENCES media(id) ON DELETE CASCADE,FOREIGN KEY(genreId) REFERENCES genres(id) ON DELETE CASCADE)");
            old.execSQL("CREATE TABLE user_progress(mediaId INTEGER NOT NULL PRIMARY KEY,currentProgress REAL NOT NULL,rating INTEGER,trackingStatus TEXT,notes TEXT,lastUpdatedAt INTEGER NOT NULL,FOREIGN KEY(mediaId) REFERENCES media(id) ON DELETE CASCADE)");
            old.execSQL("CREATE TABLE story_memory(mediaId INTEGER NOT NULL PRIMARY KEY,mainCharacterName TEXT,storySummary TEXT,lastStoryPoint TEXT,importantNotes TEXT,FOREIGN KEY(mediaId) REFERENCES media(id) ON DELETE CASCADE)");
            old.execSQL("CREATE TABLE characters(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,mediaId INTEGER NOT NULL,name TEXT,role TEXT,description TEXT,FOREIGN KEY(mediaId) REFERENCES media(id) ON DELETE CASCADE)");
            old.execSQL("INSERT INTO media VALUES(1,'Existing title','manga',NULL,'ongoing',10,10,0)");
            old.execSQL("INSERT INTO user_progress VALUES(1,12.5,8,'reading','Keep notes',12345)");
            old.execSQL("INSERT INTO genres VALUES(1,'Fantasy','#9CBFFF'),(2,'fantasy','#C3ACF1')");
            old.execSQL("INSERT INTO media_genres VALUES(1,2)");
            old.setVersion(1);old.close();
            room=Room.databaseBuilder(context,WatlisDatabase.class,name).addMigrations(WatlisDatabase.MIGRATION_1_2,WatlisDatabase.MIGRATION_2_3,WatlisDatabase.MIGRATION_3_4,WatlisDatabase.MIGRATION_4_5).build();
            assertEquals("Existing title",room.mediaDao().getById(1).title);
            assertEquals(0.5f,room.mediaDao().getById(1).coverPositionX,0);
            assertEquals(0.5f,room.mediaDao().getById(1).coverPositionY,0);
            assertEquals(1f,room.mediaDao().getById(1).coverZoom,0);
            assertEquals(12.5,room.progressDao().get(1).currentProgress,0);
            assertEquals(12345,room.progressDao().get(1).lastUpdatedAt);
            assertEquals(1,room.genreDao().getAll().size());
            assertEquals(1,room.genreDao().forMedia(1).get(0).id);
            assertEquals(4,room.mediaTypeDao().getAll().size());
            assertTrue(room.mediaTypeDao().get("anime").usesEpisodes);
            assertFalse(room.mediaTypeDao().get("manga").usesEpisodes);
        } finally {
            if(room!=null)room.close();
            context.deleteDatabase(name);
        }
    }
}
