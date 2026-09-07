package com.watlis.app;

import android.content.Context;
import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.watlis.app.data.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RepositoryInstrumentedTest {
    private WatlisDatabase db;
    private WatlisRepository repository;
    @Before public void setup() {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        db=Room.inMemoryDatabaseBuilder(context,WatlisDatabase.class).build();
        repository=new WatlisRepository(db);
        repository.refresh();
    }
    @After public void close(){db.close();}
    private long media(String title,Integer rating) {
        MediaEntity m=new MediaEntity();m.title=title;
        UserProgressEntity p=new UserProgressEntity();p.rating=rating;p.currentProgress=12.5;
        return repository.saveMedia(m,p,Collections.emptyList());
    }
    @Test public void rapidProgressPreservesDecimalsAndMetadataDoesNotChangeRecency() {
        long id=media("First",null);
        long time=repository.progress(id).lastUpdatedAt;
        repository.updateProgress(id,12.5);
        assertEquals(time,repository.progress(id).lastUpdatedAt);
        repository.favorite(id);
        assertEquals(time,repository.progress(id).lastUpdatedAt);
        MediaEntity edit=repository.media(id);edit.title="Renamed";
        repository.saveMedia(edit,repository.progress(id),Collections.emptyList());
        assertEquals(time,repository.progress(id).lastUpdatedAt);
        for(int i=0;i<20;i++)repository.incrementProgress(id,1);
        assertEquals(32.5,repository.progress(id).currentProgress,0);
        for(int i=0;i<40;i++)repository.incrementProgress(id,-1);
        assertEquals(0,repository.progress(id).currentProgress,0);
        assertEquals("plan_to_read",repository.progress(id).trackingStatus);
    }
    @Test public void normalizedGenresEnforceUniquenessAndDeleteOnlyAssociations() {
        long id=media("Title",8);
        GenreEntity g=new GenreEntity();g.name="Fantasy";g.color="#9CBFFF";
        g.id=repository.addGenre(g);
        GenreEntity duplicate=new GenreEntity();duplicate.name="FANTASY";
        try{db.genreDao().insert(duplicate);fail("Database must reject duplicate names");}
        catch(android.database.sqlite.SQLiteConstraintException expected){}
        repository.saveMedia(repository.media(id),repository.progress(id),Collections.singletonList(g.id));
        assertEquals(1,repository.genresFor(id).size());
        repository.deleteGenre(g.id);
        assertNotNull(repository.media(id));assertTrue(repository.genresFor(id).isEmpty());
    }
    @Test public void deletionCascadesAndAverageExcludesUnrated() {
        long id=media("One",8);media("One",null);media("Three",10);
        assertEquals(9.0,repository.averageRating(),0);
        StoryMemoryEntity story=new StoryMemoryEntity();story.mediaId=id;story.storySummary="Plot";repository.saveStory(story);
        CharacterEntity c=new CharacterEntity();c.mediaId=id;c.name="Main character";repository.saveCharacter(c);
        GenreEntity g=new GenreEntity();g.name="Action";g.id=repository.addGenre(g);
        repository.saveMedia(repository.media(id),repository.progress(id),Collections.singletonList(g.id));
        repository.deleteMedia(repository.media(id));
        assertNull(db.progressDao().get(id));assertNull(db.storyDao().get(id));
        assertTrue(db.storyDao().characters(id).isEmpty());assertTrue(db.genreDao().forMedia(id).isEmpty());
        assertEquals(1,repository.genres().size());assertEquals(2,repository.media().size());
    }
    @Test public void invalidProgressIsRejectedWithoutMutation() {
        long id=media("Validation",null);
        for(double value:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            try{repository.updateProgress(id,value);fail("Invalid progress accepted");}
            catch(IllegalArgumentException expected){}
        }
        assertEquals(12.5,repository.progress(id).currentProgress,0);
    }
    @Test public void coverPositionPersistsWithoutChangingProgressRecency() {
        long id=media("Cover",null);
        long updated=repository.progress(id).lastUpdatedAt;
        MediaEntity m=repository.media(id);m.coverPositionX=0.2f;m.coverPositionY=0.85f;
        repository.saveMedia(m,repository.progress(id),Collections.emptyList());
        assertEquals(0.2f,db.mediaDao().getById(id).coverPositionX,0);
        assertEquals(0.85f,db.mediaDao().getById(id).coverPositionY,0);
        assertEquals(updated,db.progressDao().get(id).lastUpdatedAt);
        m.coverPositionX=Float.NaN;
        try{repository.saveMedia(m,repository.progress(id),Collections.emptyList());fail("Invalid position accepted");}
        catch(IllegalArgumentException expected){}
        assertEquals(0.2f,db.mediaDao().getById(id).coverPositionX,0);
    }
}
