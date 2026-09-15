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
public class ProgressHistoryInstrumentedTest {
    private WatlisDatabase db;
    private WatlisRepository repo;
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    @Before public void setup() {db=Room.inMemoryDatabaseBuilder(context,WatlisDatabase.class).build();repo=new WatlisRepository(db);repo.refresh();}
    @After public void close() {db.close();}
    private long media(double progress) {
        MediaEntity m=new MediaEntity();m.title="History fixture";
        UserProgressEntity p=new UserProgressEntity();p.currentProgress=progress;p.notes="Keep notes";
        return repo.saveMedia(m,p,Collections.emptyList());
    }
    private List<ProgressHistoryEntity> history(long id) {return db.progressHistoryDao().forBackup(id);}

    @Test public void rapidDecimalUpdatesNoOpsAndUndoPreserveProgressAndRecency() {
        long id=media(24.5), baselineTime=repo.progress(id).lastUpdatedAt;
        assertTrue(history(id).isEmpty());assertNull(repo.updateProgress(id,24.5));
        for(int i=0;i<35;i++)repo.incrementProgress(id,1);
        assertEquals(59.5,repo.progress(id).currentProgress,0);assertEquals(35,history(id).size());
        for(int i=0;i<35;i++) {
            ProgressHistoryEntity entry=history(id).get(i);
            assertEquals(24.5+i,entry.fromProgress,0);assertEquals(25.5+i,entry.toProgress,0);
            assertEquals("reading",entry.kind);assertEquals("Chapter",entry.unit);
        }
        ProgressHistoryEntity first=history(id).get(0), latest=history(id).get(34);
        assertEquals(baselineTime,first.beforeUpdatedAt);
        assertTrue(repo.undoProgress(id,latest.token));
        assertEquals(58.5,repo.progress(id).currentProgress,0);
        assertEquals(latest.beforeUpdatedAt,repo.progress(id).lastUpdatedAt);
        assertEquals("undo",history(id).get(35).kind);assertEquals(latest.token,history(id).get(35).undoOf);
        assertFalse(repo.undoProgress(id,latest.token));assertFalse(repo.undoProgress(id,first.token));
        repo.updateProgress(id,0);int count=history(id).size();assertNull(repo.incrementProgress(id,-1));
        assertEquals(count,history(id).size());assertEquals("Keep notes",repo.progress(id).notes);
    }

    @Test public void staleUndoCannotOverwriteNewerChangeEvenAtSameValue() {
        long id=media(24.5);
        ProgressHistoryEntity old=repo.incrementProgress(id,1);
        repo.incrementProgress(id,1);ProgressHistoryEntity latest=repo.incrementProgress(id,-1);
        assertEquals(old.toProgress,latest.toProgress,0);
        assertFalse(repo.undoProgress(id,old.token));assertEquals(25.5,repo.progress(id).currentProgress,0);
        long other=media(3);repo.incrementProgress(other,1);
        assertTrue(repo.undoProgress(id,latest.token));assertEquals(26.5,repo.progress(id).currentProgress,0);
        assertFalse(repo.undoProgress(other,latest.token));
    }

    @Test public void metadataDoesNotCreateHistoryAndEditorProgressIsACorrection() {
        long id=media(12.5);
        MediaEntity m=repo.media(id);m.title="Renamed";m.coverImage="content://test/photo";
        repo.saveMedia(m,repo.progress(id),Collections.emptyList());repo.favorite(id);
        repo.updateTracking(id,"reading",9,"Updated notes");
        CharacterEntity c=new CharacterEntity();c.mediaId=id;c.name="Guardian";repo.saveCharacter(c);
        StoryMemoryEntity story=new StoryMemoryEntity();story.mediaId=id;story.storySummary="Summary";repo.saveStory(story);
        assertTrue(history(id).isEmpty());
        UserProgressEntity p=repo.progress(id);p.currentProgress=20.25;
        ProgressHistoryEntity[] result={null};repo.saveMedia(m,p,Collections.emptyList(),change -> result[0]=change);
        assertNotNull(result[0]);assertEquals("correction",result[0].kind);
        assertEquals(12.5,result[0].fromProgress,0);assertEquals(20.25,result[0].toProgress,0);
        assertTrue(repo.undoProgress(id,result[0].token));
        assertEquals("Updated notes",repo.progress(id).notes);assertEquals("Renamed",repo.media(id).title);
        assertEquals(9,repo.progress(id).rating.intValue());
    }

    @Test public void historyInsertFailureRollsBackProgressUndoAndMediaEdit() {
        long id=media(7.5);long time=repo.progress(id).lastUpdatedAt;
        db.getOpenHelper().getWritableDatabase().execSQL("CREATE TRIGGER fail_history BEFORE INSERT ON progress_history BEGIN SELECT RAISE(ABORT,'test failure'); END");
        try {repo.incrementProgress(id,1);fail("Progress must roll back");}catch(android.database.sqlite.SQLiteException expected) {}
        assertEquals(7.5,db.progressDao().get(id).currentProgress,0);assertEquals(time,db.progressDao().get(id).lastUpdatedAt);
        MediaEntity m=repo.media(id);m.title="Must not save";
        UserProgressEntity p=repo.progress(id);p.currentProgress=9;
        try {repo.saveMedia(m,p,Collections.emptyList());fail("Media edit must roll back");}catch(android.database.sqlite.SQLiteException expected) {}
        assertEquals("History fixture",db.mediaDao().getById(id).title);assertTrue(history(id).isEmpty());
        db.getOpenHelper().getWritableDatabase().execSQL("DROP TRIGGER fail_history");repo.refresh();
        ProgressHistoryEntity change=repo.incrementProgress(id,1);
        db.getOpenHelper().getWritableDatabase().execSQL("CREATE TRIGGER fail_history BEFORE INSERT ON progress_history BEGIN SELECT RAISE(ABORT,'test failure'); END");
        try {repo.undoProgress(id,change.token);fail("Undo must roll back");}catch(android.database.sqlite.SQLiteException expected) {}
        assertEquals(8.5,db.progressDao().get(id).currentProgress,0);assertEquals(1,history(id).size());
    }

    @Test public void keysetPagesDoNotDuplicateOrSkipWhenNewChangesArrive() {
        long id=media(0);for(int i=0;i<85;i++)repo.incrementProgress(id,1);
        List<ProgressHistoryEntity> first=repo.progressHistory(id,Long.MAX_VALUE,30);
        assertEquals(30,first.size());repo.incrementProgress(id,1);
        Set<Long> seen=new HashSet<>();for(ProgressHistoryEntity item:first)seen.add(item.id);
        long cursor=first.get(29).id;
        while(true) {
            List<ProgressHistoryEntity> page=repo.progressHistory(id,cursor,30);if(page.isEmpty())break;
            assertTrue(page.size()<=30);
            for(ProgressHistoryEntity item:page){assertTrue(item.id<cursor);assertTrue(seen.add(item.id));}
            cursor=page.get(page.size()-1).id;
        }
        assertEquals(85,seen.size());assertFalse(seen.contains(db.progressHistoryDao().latest(id).id));
    }

    @Test public void backupsKeepHistoryUndoAndUnitsAndRejectCorruptionWithoutDataLoss() throws Exception {
        long id=media(12.5);ProgressHistoryEntity change=repo.incrementProgress(id,1);repo.undoProgress(id,change.token);
        MediaEntity m=repo.media(id);m.type="anime";repo.saveMedia(m,repo.progress(id),Collections.emptyList());
        ProgressHistoryEntity last=repo.updateProgress(id,9.25);
        String backup=repo.exportToJson();repo.importFromJson(backup);
        assertEquals(3,history(id).size());assertEquals("Chapter",history(id).get(0).unit);assertEquals("Episode",history(id).get(2).unit);
        assertTrue(repo.undoProgress(id,last.token));assertEquals(12.5,repo.progress(id).currentProgress,0);
        String restored=repo.exportToJson();org.json.JSONObject broken=new org.json.JSONObject(restored);
        broken.getJSONArray("media").getJSONObject(0).getJSONArray("progressHistory").getJSONObject(0).put("to",900);
        try {repo.importFromJson(broken.toString());fail("Invalid history accepted");}catch(IllegalArgumentException expected) {}
        assertEquals(12.5,db.progressDao().get(id).currentProgress,0);assertEquals(4,history(id).size());
        org.json.JSONObject legacy=new org.json.JSONObject(backup);legacy.put("version",4);
        legacy.getJSONArray("media").getJSONObject(0).remove("progressHistory");
        repo.importFromJson(legacy.toString());assertTrue(history(id).isEmpty());assertEquals(9.25,repo.progress(id).currentProgress,0);
        ProgressHistoryEntity fresh=repo.incrementProgress(id,1);assertEquals(9.25,fresh.fromProgress,0);
        assertFalse(repo.undoProgress(id,last.token));repo.deleteMedia(repo.media(id));assertTrue(history(id).isEmpty());
    }

    @Test public void reopeningDatabaseRetainsLatestEligibleUndo() {
        String name="watlis-history-reopen-"+UUID.randomUUID()+".db";
        WatlisDatabase disk=Room.databaseBuilder(context,WatlisDatabase.class,name).build();
        try {
            WatlisRepository persistent=new WatlisRepository(disk);MediaEntity m=new MediaEntity();m.title="Persisted history";
            UserProgressEntity p=new UserProgressEntity();p.currentProgress=3.5;
            persistent.saveMedia(m,p,Collections.emptyList());ProgressHistoryEntity change=persistent.incrementProgress(m.id,1);
            disk.close();disk=Room.databaseBuilder(context,WatlisDatabase.class,name).build();persistent=new WatlisRepository(disk);persistent.refresh();
            assertEquals(change.token,persistent.progressHistory(m.id,Long.MAX_VALUE,30).get(0).token);
            assertTrue(persistent.undoProgress(m.id,change.token));assertEquals(3.5,persistent.progress(m.id).currentProgress,0);
        } finally {disk.close();context.deleteDatabase(name);}
    }

    @Test public void concurrentWritesAndUndoAreSerializedByDatabaseTransaction() throws Exception {
        long id=media(0);WatlisRepository second=new WatlisRepository(db);second.refresh();
        java.util.concurrent.ExecutorService workers=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<?> a=workers.submit(() -> {for(int i=0;i<15;i++)repo.incrementProgress(id,1);});
            java.util.concurrent.Future<?> b=workers.submit(() -> {for(int i=0;i<15;i++)second.incrementProgress(id,1);});
            a.get();b.get();assertEquals(30,db.progressDao().get(id).currentProgress,0);assertEquals(30,history(id).size());
            ProgressHistoryEntity last=db.progressHistoryDao().latest(id);
            java.util.concurrent.Future<?> newer=workers.submit(() -> second.incrementProgress(id,1));
            java.util.concurrent.Future<Boolean> undo=workers.submit(() -> repo.undoProgress(id,last.token));
            newer.get();boolean undone=undo.get();
            assertEquals(undone?30:31,db.progressDao().get(id).currentProgress,0);
            assertEquals(undone?32:31,history(id).size());
        } finally {workers.shutdownNow();}
    }
}
