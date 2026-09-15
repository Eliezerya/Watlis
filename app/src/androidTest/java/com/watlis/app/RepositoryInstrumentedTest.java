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
    @Test public void mediaTypeRenameAndReassignmentKeepTrackingAndRelations() {
        MediaTypeEntity type=new MediaTypeEntity();type.name="Web novel";
        repository.saveMediaType(type);
        long id=media("Custom",8);
        MediaEntity m=repository.media(id);m.type=type.key;
        GenreEntity g=new GenreEntity();g.name="Adventure";g.id=repository.addGenre(g);
        repository.saveMedia(m,repository.progress(id),Collections.singletonList(g.id));
        long recency=repository.progress(id).lastUpdatedAt;
        StoryMemoryEntity story=new StoryMemoryEntity();story.mediaId=id;story.storySummary="Remember this";
        repository.saveStory(story);
        type.name="Audio drama";type.usesEpisodes=true;repository.saveMediaType(type);
        assertEquals(type.key,repository.media(id).type);
        assertEquals("Audio drama",repository.mediaType(type.key).name);
        assertTrue(repository.mediaType(type.key).usesEpisodes);
        try {repository.deleteMediaType(type.key,null);fail("Must require replacement");}
        catch(IllegalArgumentException expected) {}
        assertNotNull(repository.mediaType(type.key));
        repository.deleteMediaType(type.key,"anime");
        assertNull(repository.mediaType(type.key));
        assertEquals("anime",repository.media(id).type);
        assertEquals(12.5,repository.progress(id).currentProgress,0);
        assertEquals(recency,repository.progress(id).lastUpdatedAt);
        assertEquals(g.id,repository.genresFor(id).get(0).id);
        assertEquals("Remember this",repository.story(id).storySummary);
    }
    @Test public void mediaTypesRejectDuplicatesAndKeepOneType() {
        MediaTypeEntity duplicate=new MediaTypeEntity();duplicate.name=" MANGA ";
        try {repository.saveMediaType(duplicate);fail("Duplicate accepted");}
        catch(IllegalArgumentException expected) {}
        for(String key:new String[]{"anime","manhwa","manhua"})repository.deleteMediaType(key,null);
        try {repository.deleteMediaType("manga",null);fail("Last type deleted");}
        catch(IllegalArgumentException expected) {}
        assertEquals(1,repository.mediaTypes().size());
        MediaEntity invalid=new MediaEntity();invalid.title="Invalid";invalid.type="missing";
        try {repository.saveMedia(invalid,new UserProgressEntity(),Collections.emptyList());fail("Unknown type accepted");}
        catch(IllegalArgumentException expected) {}
        assertEquals(0,repository.media().size());
    }
    @Test public void backupRoundTripKeepsTypesCoverPositionAndRollsBackInvalidData() throws Exception {
        MediaTypeEntity type=new MediaTypeEntity();type.name="Web series";type.usesEpisodes=true;
        repository.saveMediaType(type);
        long id=media("Backup",9);MediaEntity m=repository.media(id);m.type=type.key;m.coverPositionY=.8f;m.coverZoom=2.25f;
        repository.saveMedia(m,repository.progress(id),Collections.emptyList());
        long recency=repository.progress(id).lastUpdatedAt;
        CharacterEntity character=new CharacterEntity();character.mediaId=id;character.name="Guardian";
        character.image="content://test/character-picture";character.description="Original description";
        repository.saveCharacter(character);
        String backup=repository.exportToJson();
        repository.deleteMedia(m);repository.deleteMediaType(type.key,null);
        repository.importFromJson(backup);
        assertEquals(type.key,repository.media(id).type);
        assertTrue(repository.mediaType(type.key).usesEpisodes);
        assertEquals(.8f,repository.media(id).coverPositionY,0);
        assertEquals(2.25f,repository.media(id).coverZoom,0);
        assertEquals(recency,repository.progress(id).lastUpdatedAt);
        assertEquals(character.image,repository.characters(id).get(0).image);
        assertEquals(character.description,repository.characters(id).get(0).description);
        org.json.JSONObject broken=new org.json.JSONObject(backup);broken.remove("media");
        try {repository.importFromJson(broken.toString());fail("Invalid backup accepted");}
        catch(IllegalArgumentException expected) {}
        assertNotNull(db.mediaDao().getById(id));
        assertNotNull(db.mediaTypeDao().get(type.key));
        org.json.JSONObject legacy=new org.json.JSONObject(backup);legacy.put("version",1);legacy.remove("mediaTypes");
        legacy.getJSONArray("media").getJSONObject(0).put("type","manga");
        legacy.getJSONArray("media").getJSONObject(0).remove("coverZoom");
        legacy.getJSONArray("media").getJSONObject(0).getJSONArray("characters").getJSONObject(0).remove("image");
        repository.importFromJson(legacy.toString());
        assertEquals(4,repository.mediaTypes().size());
        assertEquals("manga",repository.media(id).type);
        assertEquals(1f,repository.media(id).coverZoom,0);
        assertNull(repository.characters(id).get(0).image);
    }
    @Test public void coverPositionPersistsWithoutChangingProgressRecency() {
        long id=media("Cover",null);
        long updated=repository.progress(id).lastUpdatedAt;
        MediaEntity m=repository.media(id);m.coverPositionX=0.2f;m.coverPositionY=0.85f;m.coverZoom=1.5f;
        repository.saveMedia(m,repository.progress(id),Collections.emptyList());
        assertEquals(0.2f,db.mediaDao().getById(id).coverPositionX,0);
        assertEquals(0.85f,db.mediaDao().getById(id).coverPositionY,0);
        assertEquals(1.5f,db.mediaDao().getById(id).coverZoom,0);
        assertEquals(updated,db.progressDao().get(id).lastUpdatedAt);
        m.coverPositionX=Float.NaN;
        try{repository.saveMedia(m,repository.progress(id),Collections.emptyList());fail("Invalid position accepted");}
        catch(IllegalArgumentException expected){}
        assertEquals(0.2f,db.mediaDao().getById(id).coverPositionX,0);
        m.coverPositionX=.2f;
        for(float zoom:new float[]{0,4,Float.NaN,Float.POSITIVE_INFINITY}) {
            m.coverZoom=zoom;
            try {repository.saveMedia(m,repository.progress(id),Collections.emptyList());fail("Invalid zoom accepted");}
            catch(IllegalArgumentException expected) {}
        }
        assertEquals(1.5f,db.mediaDao().getById(id).coverZoom,0);
    }
}
