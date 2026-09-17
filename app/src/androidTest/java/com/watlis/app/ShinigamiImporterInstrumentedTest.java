package com.watlis.app;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.room.Room;
import com.watlis.app.data.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.IOException;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ShinigamiImporterInstrumentedTest {
    static final String CHAPTER="c63f24c0-eb50-4539-a56d-7ba076feaae6";
    static final String MANGA="4bf6c017-842e-48a1-8a2a-f6160c1d8d44";
    static final String LINK="https://11.shinigami.asia/chapter/"+CHAPTER;
    static String chapter() { return "{\"retcode\":0,\"data\":{\"chapter_id\":\""+CHAPTER+"\",\"manga_id\":\""+MANGA+"\",\"chapter_number\":81.5}}"; }
    static String manga(String title, String genre) {
        try {
            JSONObject data=new JSONObject("{\"manga_id\":\""+MANGA+"\",\"title\":\"Fixture\",\"description\":\"Synopsis line 1\\nLine 2\",\"alternative_title\":\"Alternative\",\"release_year\":\"2023\",\"status\":1,\"user_rate\":8.5,\"latest_chapter_number\":138,\"cover_portrait_url\":\"https://assets.shngm.id/portrait.jpg\",\"cover_image_url\":\"https://assets.shngm.id/landscape.jpg\",\"taxonomy\":{\"Format\":[{\"name\":\"Manhwa\"}],\"Genre\":[{\"name\":\"Action\"},{\"name\":\"action\"}],\"Author\":[{\"name\":\"Author A\"},{\"name\":\"Author B\"}]}}");
            data.put("title",title);
            if(genre!=null)data.getJSONObject("taxonomy").getJSONArray("Genre").getJSONObject(0).put("name",genre);
            return new JSONObject().put("retcode",0).put("data",data).toString();
        } catch(Exception error){throw new AssertionError(error);}
    }

    @Test public void acceptsOnlyExactHttpsProviderChapterLinks() {
        assertEquals(CHAPTER,ShinigamiImporter.chapterId("  "+LINK+"/?from=reader#end  "));
        assertEquals(CHAPTER,ShinigamiImporter.chapterId(LINK.replace("11.","")));
        for(String invalid:Arrays.asList("Title",LINK.replace("https:","http:"),LINK.replace("11.shinigami.asia","evil.shinigami.asia.attacker.com"),
                LINK.replace("11.shinigami.asia","shinigami.asia@evil.com"),LINK.replace("11.shinigami.asia","user@11.shinigami.asia"),
                LINK.replace("11.shinigami.asia","11.shinigami.asia:8080"),LINK+"/extra",LINK.replace("chapter/","manga/"),LINK.replace(CHAPTER,"bad-id")))
            assertNull(invalid,ShinigamiImporter.chapterId(invalid));
    }

    @Test public void usesOpenedDecimalChapterAndPortraitWithoutPersonalRatingOrStatusGuess() throws Exception {
        List<String> requests=new ArrayList<>();
        ShinigamiImporter.Result result=new ShinigamiImporter(url->{requests.add(url);return requests.size()==1?chapter():manga("Star-Embracing Swordmaster",null);}).fetch(LINK);
        assertEquals(Arrays.asList("https://api.shngm.io/v1/chapter/detail/"+CHAPTER,"https://api.shngm.io/v1/manga/detail/"+MANGA),requests);
        assertEquals(81.5,result.progress,0);assertEquals("Star-Embracing Swordmaster",result.title);
        assertEquals("Manhwa",result.format);assertEquals(Collections.singletonList("Action"),result.genres);
        assertEquals("https://assets.shngm.id/portrait.jpg",result.cover);assertNull(result.releaseStatus);
        assertTrue(result.notes.contains("Synopsis line 1\nLine 2"));assertTrue(result.notes.contains("Author A, Author B"));
        assertTrue(result.notes.contains("8.5/10"));assertTrue(result.notes.contains(LINK));
    }

    @Test public void rejectsMalformedResponsesWrongIdsAndInvalidProgress() throws Exception {
        for(String bad:Arrays.asList("not json","{}","{\"retcode\":5,\"data\":{}}",chapter().replace(CHAPTER,MANGA),
                chapter().replace(MANGA,"../other"),chapter().replace("81.5","-1"),chapter().replace("81.5","\"NaN\""))) {
            try {new ShinigamiImporter(url->bad).fetch(LINK);fail(bad);}catch(IOException expected) { }
        }
        try {new ShinigamiImporter(url->url.contains("chapter/detail")?chapter():manga("Title",null).replace(MANGA,CHAPTER)).fetch(LINK);fail();}
        catch(IOException expected) { }
        try {new ShinigamiImporter(url->{throw new java.net.SocketTimeoutException();}).fetch(LINK);fail();}
        catch(java.net.SocketTimeoutException expected) { }
    }

    @Test public void optionalMetadataAndUnsafeCoversDoNotBreakImport() throws Exception {
        JSONObject value=new JSONObject(manga("Title",null));JSONObject data=value.getJSONObject("data");
        data.put("cover_portrait_url","https://evil.com/tracker");data.put("cover_image_url","file:///private/file");
        data.remove("taxonomy");data.put("description",JSONObject.NULL);data.put("status","completed");
        ShinigamiImporter.Result result=new ShinigamiImporter(url->url.contains("chapter/detail")?chapter():value.toString()).fetch(LINK);
        assertEquals("",result.cover);assertEquals("",result.format);assertTrue(result.genres.isEmpty());assertEquals("completed",result.releaseStatus);
        ShinigamiImporter cancelled=new ShinigamiImporter(url->chapter());cancelled.cancel();
        try {cancelled.fetch(LINK);fail();}catch(IOException expected) { }
    }

    @Test public void importedTaxonomyAndMediaSaveAtomicallyWithoutInventedHistory() {
        WatlisDatabase db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().getTargetContext(),WatlisDatabase.class).build();
        try {
            WatlisRepository repo=new WatlisRepository(db);
            GenreEntity known=new GenreEntity();known.name="Action";known.color="#FFAAAA";long knownId=repo.addGenre(known);
            MediaEntity m=new MediaEntity();m.title="Imported";m.type="imported_type";
            UserProgressEntity progress=new UserProgressEntity();progress.currentProgress=81.5;
            try {repo.saveImportedMedia(m,progress,Collections.emptyList(),Arrays.asList("New genre",""),"New format",change->{});fail();}
            catch(IllegalArgumentException expected) { }
            assertEquals(0,m.id);assertEquals("imported_type",m.type);assertNull(db.genreDao().findByName("New genre"));
            assertNull(db.mediaTypeDao().findByName("New format"));assertTrue(db.mediaDao().getAll().isEmpty());
            repo.saveImportedMedia(m,progress,Collections.emptyList(),Arrays.asList("action","New genre"),"New format",change->assertNull(change));
            assertEquals(81.5,db.progressDao().get(m.id).currentProgress,0);assertEquals(2,repo.genresFor(m.id).size());
            assertEquals(knownId,repo.findGenre("action").id);assertEquals("#FFAAAA",repo.findGenre("Action").color);
            assertNotNull(db.mediaTypeDao().findByName("New format"));assertTrue(db.progressHistoryDao().forBackup(m.id).isEmpty());
        } finally {db.close();}
    }

    @Test public void liveExampleFetchesChapter81() throws Exception {
        org.junit.Assume.assumeTrue("Opt-in live network smoke test", "true".equals(InstrumentationRegistry.getArguments().getString("liveShinigami")));
        ShinigamiImporter.Result result=new ShinigamiImporter().fetch(LINK);
        assertEquals("Star-Embracing Swordmaster",result.title);assertEquals(81,result.progress,0);assertEquals("Manhwa",result.format);
        assertFalse(result.cover.isEmpty());assertTrue(result.genres.contains("Action"));
        java.util.concurrent.CountDownLatch copied=new java.util.concurrent.CountDownLatch(1);
        java.io.File[] thumbnail={null};
        CoverStore.get(InstrumentationRegistry.getInstrumentation().getTargetContext()).ensure(result.cover,file->{thumbnail[0]=file;copied.countDown();});
        assertTrue(copied.await(20,java.util.concurrent.TimeUnit.SECONDS));assertNotNull("Imported cover is cached offline",thumbnail[0]);
        android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        android.graphics.BitmapFactory.decodeFile(thumbnail[0].getAbsolutePath(),bounds);
        assertTrue(bounds.outWidth>0 && bounds.outWidth<=CoverStore.MAX_EDGE);
        assertTrue(bounds.outHeight>0 && bounds.outHeight<=CoverStore.MAX_EDGE);
    }
}
