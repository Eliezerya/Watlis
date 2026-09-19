package com.watlis.app;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.watlis.app.data.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FloatingChapterInstrumentedTest {
    private WatlisDatabase db;
    private WatlisRepository repo;
    private FloatingChapterStore store;
    @Before public void setup() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().getTargetContext(), WatlisDatabase.class).build();
        repo = new WatlisRepository(db); repo.refresh(); store = new FloatingChapterStore(db);
    }
    @After public void close() { db.close(); }
    private MediaEntity media(double progress, String type) {
        MediaEntity media = new MediaEntity(); media.title = "Floating fixture"; media.type = type;
        UserProgressEntity p = new UserProgressEntity(); p.currentProgress = progress; p.notes = "Keep this note"; p.rating = 8;
        repo.saveMedia(media, p, Collections.emptyList()); return media;
    }
    @Test public void decimalsHistoryUndoAndBackupUseExistingRules() throws Exception {
        MediaEntity m = media(24.5, "manhwa");
        assertEquals(25.5, store.increment(m.id, m.createdAt, 1).progress, 0);
        ProgressHistoryEntity first = db.progressHistoryDao().latest(m.id);
        assertEquals("reading", first.kind);
        assertEquals(24.5, store.increment(m.id, m.createdAt, -1).progress, 0);
        ProgressHistoryEntity last = db.progressHistoryDao().latest(m.id);
        assertEquals("correction", last.kind); assertFalse(repo.undoProgress(m.id, first.token));
        assertTrue(repo.undoProgress(m.id, last.token));
        assertEquals(25.5, store.read(m.id, m.createdAt).progress, 0);
        String backup = repo.exportToJson(); repo.importFromJson(backup);
        assertEquals(25.5, new FloatingChapterStore(db).read(m.id, m.createdAt).progress, 0);
        assertEquals(3, db.progressHistoryDao().forBackup(m.id).size());
        assertEquals("24.5", FloatingChapterView.format(24.5)); assertEquals("0", FloatingChapterView.format(0));
    }
    @Test public void rapidAndConcurrentWritersReadPersistedProgressWithoutLostTaps() throws Exception {
        MediaEntity m = media(1.25, "manga"); ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> updates = new ArrayList<>();
            for (int i = 0; i < 40; i++) updates.add(pool.submit(() -> new FloatingChapterStore(db).increment(m.id, m.createdAt, 1)));
            for (Future<?> update : updates) update.get(10, TimeUnit.SECONDS);
            repo.incrementProgress(m.id, 1); store.increment(m.id, m.createdAt, -1);
            assertEquals(41.25, store.read(m.id, m.createdAt).progress, 0);
            assertEquals(42, db.progressHistoryDao().forBackup(m.id).size());
            assertEquals("Keep this note", db.progressDao().get(m.id).notes);
            assertEquals(8, db.progressDao().get(m.id).rating.intValue());
        } finally { pool.shutdownNow(); }
    }
    @Test public void lowerBoundAndEpisodesDoNotInventSessions() {
        MediaEntity m = media(.5, "anime");
        assertEquals("Episode", store.read(m.id, m.createdAt).unit);
        assertEquals(0, store.increment(m.id, m.createdAt, -1).progress, 0);
        assertEquals(0, store.increment(m.id, m.createdAt, -1).progress, 0);
        assertEquals(1, db.progressHistoryDao().forBackup(m.id).size());
        assertEquals("Episode", db.progressHistoryDao().latest(m.id).unit);
    }
    @Test public void failedHistoryInsertRollsBackFloatingProgress() {
        MediaEntity m = media(5.5, "manga");
        db.getOpenHelper().getWritableDatabase().execSQL("CREATE TRIGGER fail_floating BEFORE INSERT ON progress_history BEGIN SELECT RAISE(ABORT,'test failure'); END");
        try { store.increment(m.id, m.createdAt, 1); fail("Expected rollback"); } catch (android.database.sqlite.SQLiteException expected) { }
        assertEquals(5.5, store.read(m.id, m.createdAt).progress, 0);
        assertTrue(db.progressHistoryDao().forBackup(m.id).isEmpty());
    }
    @Test public void missingOrReplacedTitlesCannotBeChanged() {
        MediaEntity m = media(7, "manga");
        assertNull(store.increment(m.id, m.createdAt + 1, 1));
        assertEquals(7, store.read(m.id, m.createdAt).progress, 0);
        db.mediaDao().delete(m); assertNull(store.read(m.id, m.createdAt)); assertNull(store.increment(m.id, m.createdAt, 1));
    }
    @Test public void metadataRefreshDoesNotCreateReadingHistory() {
        MediaEntity m = media(7, "manga"); m.title = "Changed title"; db.mediaDao().update(m);
        assertEquals("Changed title", store.read(m.id, m.createdAt).title);
        assertTrue(db.progressHistoryDao().forBackup(m.id).isEmpty());
    }
}
