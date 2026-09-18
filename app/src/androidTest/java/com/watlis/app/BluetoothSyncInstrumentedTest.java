package com.watlis.app;

import android.content.Context;
import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.watlis.app.data.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BluetoothSyncInstrumentedTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final List<Device> devices = new ArrayList<>();
    private final class Device implements AutoCloseable {
        final WatlisDatabase db = Room.inMemoryDatabaseBuilder(context, WatlisDatabase.class).build();
        final WatlisRepository repo;
        final SyncEngine sync;
        Device() { this(false); }
        Device(boolean images) {
            repo = new WatlisRepository(db, images ? CoverStore.get(context) : null);
            sync = new SyncEngine(db, repo, UUID.randomUUID().toString()); repo.refresh(); devices.add(this);
        }
        long add(String name, double progress) {
            MediaEntity m = new MediaEntity(); m.title = name;
            UserProgressEntity p = new UserProgressEntity(); p.currentProgress = progress; p.notes = "Original note";
            return repo.saveMedia(m, p, Collections.emptyList());
        }
        long first() { return repo.media().get(0).id; }
        @Override public void close() { db.close(); }
    }
    @After public void close() { for (Device d : devices) d.close(); }
    private SyncDocument resolve(SyncDocument a, SyncDocument b, String actor, int choice, int duplicateChoice) throws Exception {
        SyncDocument.Plan plan = new SyncDocument.Plan(a, b, actor);
        while (!plan.conflicts.isEmpty()) plan.choose(plan.conflicts.get(0), choice);
        Set<String> separate = new HashSet<>(); SyncDocument.Conflict duplicate;
        while ((duplicate = plan.nextDuplicate(separate)) != null) {
            separate.add(duplicate.here.key + duplicate.there.key); separate.add(duplicate.there.key + duplicate.here.key);
            plan.choose(duplicate, duplicateChoice);
        }
        return plan.finish();
    }
    private void sync(Device a, Device b) throws Exception { sync(a, b, 0, 0); }
    private void sync(Device a, Device b, int choice, int duplicateChoice) throws Exception {
        SyncDocument left = a.sync.capture(), right = b.sync.capture(), merged = resolve(left, right, a.sync.actor(), choice, duplicateChoice);
        SyncEngine.validateProposal(left, right, merged); a.sync.apply(left, merged); b.sync.apply(right, merged);
        assertEquals(a.sync.capture().fingerprint(), b.sync.capture().fingerprint());
    }
    @Test public void firstSyncRemapsNumericIdsAndRepeatedSyncDoesNotDuplicate() throws Exception {
        Device a = new Device(), b = new Device(); long id = a.add("Phone only", 24.5); b.add("Tablet only", 9.25);
        a.repo.incrementProgress(id, 1); sync(a, b);
        assertEquals(2, a.repo.media().size()); assertEquals(2, b.repo.media().size());
        String before = a.sync.capture().fingerprint(); sync(b, a); sync(a, b);
        assertEquals(before, a.sync.capture().fingerprint());
        assertEquals(1, a.db.progressHistoryDao().forBackup(id).size());
        assertEquals(25.5, a.repo.progress(id).currentProgress, 0);
    }
    @Test public void concurrentEditsRequireChoiceAndKeepProgressWithItsOwnHistory() throws Exception {
        Device a = new Device(), b = new Device(); long id = a.add("Shared", 24.5); sync(a, b); long bid = b.first();
        a.repo.incrementProgress(id, 1); b.repo.updateProgress(bid, 12.25);
        a.repo.updateTracking(id, "reading", 8, "Phone draft"); b.repo.updateTracking(bid, "on_hold", 9, "Tablet draft");
        SyncDocument.Plan plan = new SyncDocument.Plan(a.sync.capture(), b.sync.capture(), a.sync.actor());
        assertEquals(1, plan.conflicts.size()); assertFalse(plan.conflicts.get(0).duplicate);
        sync(a, b, 1, 0);
        assertEquals(12.25, a.repo.progress(id).currentProgress, 0); assertEquals("Tablet draft", a.repo.progress(id).notes);
        List<ProgressHistoryEntity> history = a.db.progressHistoryDao().forBackup(id);
        assertEquals(1, history.size()); assertEquals("correction", history.get(0).kind);
        assertTrue(a.repo.undoProgress(id, history.get(0).token)); sync(a, b);
        assertEquals(24.5, b.repo.progress(bid).currentProgress, 0);
        assertEquals("undo", b.db.progressHistoryDao().latest(bid).kind);
        assertFalse(b.repo.undoProgress(bid, history.get(0).token));
    }
    @Test public void lowerCorrectionIsNewerNotHighestProgressOrNewestWallClock() throws Exception {
        Device a = new Device(), b = new Device(); a.add("Correction", 80); sync(a, b);
        b.repo.updateProgress(b.first(), 2.75);
        b.db.getOpenHelper().getWritableDatabase().execSQL("UPDATE media SET updatedAt=1");
        assertTrue(new SyncDocument.Plan(a.sync.capture(), b.sync.capture(), a.sync.actor()).conflicts.isEmpty());
        sync(a, b); assertEquals(2.75, a.repo.progress(a.first()).currentProgress, 0);
    }
    @Test public void independentDuplicateCanMergeOrStayDistinctWithoutRepeatedPrompt() throws Exception {
        Device a = new Device(), b = new Device(); a.add("Same Title", 3); b.add("Same Title", 5);
        SyncDocument.Plan plan = new SyncDocument.Plan(a.sync.capture(), b.sync.capture(), a.sync.actor());
        assertNotNull(plan.nextDuplicate(new HashSet<>())); sync(a, b, 0, 1);
        assertEquals(1, a.repo.media().size()); assertEquals(5, a.repo.progress(a.first()).currentProgress, 0); sync(b, a);
        Device c = new Device(), d = new Device(); c.add("Two editions", 1); d.add("Two editions", 2); sync(c, d, 0, 2);
        assertEquals(2, c.repo.media().size());
        assertNull(new SyncDocument.Plan(c.sync.capture(), d.sync.capture(), c.sync.actor()).nextDuplicate(new HashSet<>()));
        sync(d, c); assertEquals(2, d.repo.media().size());
    }
    @Test public void intentionalDistinctCopiesOfLegacyBackupKeepHistoryWithoutTokenCollisions() throws Exception {
        Device a = new Device(), b = new Device(); long id = a.add("Legacy copies", 10); a.repo.incrementProgress(id, 1);
        JSONObject legacy = new JSONObject(a.repo.exportToJson()); legacy.remove("syncState"); legacy.put("version", 5);
        b.repo.importFromJson(legacy.toString()); sync(a, b, 0, 2);
        assertEquals(2, a.repo.media().size());
        Set<String> tokens = new HashSet<>();
        for (MediaEntity media : a.repo.media()) {
            List<ProgressHistoryEntity> history = a.db.progressHistoryDao().forBackup(media.id);
            assertEquals(1, history.size()); assertTrue(tokens.add(history.get(0).token));
            assertTrue(a.repo.undoProgress(media.id, history.get(0).token));
        }
        sync(a, b); for (MediaEntity media : b.repo.media()) assertEquals(10, b.repo.progress(media.id).currentProgress, 0);
    }
    @Test public void deletionIsReviewedAndTombstoneDoesNotResurrectOnThirdDevice() throws Exception {
        Device a = new Device(), b = new Device(), c = new Device(); a.add("Delete later", 7); sync(a, b); sync(a, c);
        a.repo.deleteMedia(a.repo.media(a.first()));
        SyncDocument.Plan review = new SyncDocument.Plan(a.sync.capture(), b.sync.capture(), a.sync.actor());
        assertEquals(1, review.conflicts.size()); assertTrue(review.conflicts.get(0).here.deleted()); sync(a, b);
        assertTrue(a.repo.media().isEmpty()); sync(b, c); assertTrue(c.repo.media().isEmpty()); sync(c, a);
        assertTrue(a.repo.media().isEmpty());
    }
    @Test public void deleteVersusEditCanKeepEditedVersionExplicitly() throws Exception {
        Device a = new Device(), b = new Device(); a.add("Retain", 7); sync(a, b);
        a.repo.deleteMedia(a.repo.media(a.first())); b.repo.incrementProgress(b.first(), 1); sync(a, b, 1, 0);
        assertEquals(1, a.repo.media().size()); assertEquals(8, a.repo.progress(a.first()).currentProgress, 0);
    }
    @Test public void staleReviewAndInvalidHistoryRollBackWholeApply() throws Exception {
        Device a = new Device(), b = new Device(); long id = a.add("Atomic", 4); sync(a, b);
        SyncDocument before = a.sync.capture(); b.repo.incrementProgress(b.first(), 1);
        SyncDocument merged = resolve(before, b.sync.capture(), a.sync.actor(), 0, 0);
        a.repo.updateTracking(id, "reading", null, "Changed while reviewing");
        try { a.sync.apply(before, merged); fail("Stale snapshot accepted"); } catch (IllegalStateException expected) { }
        assertEquals(4, a.repo.progress(id).currentProgress, 0); assertEquals("Changed while reviewing", a.repo.progress(id).notes);
        SyncDocument current = a.sync.capture(); JSONObject badJson = merged.json();
        JSONArray entries = badJson.getJSONArray("records");
        for (int i = 0; i < entries.length(); i++) if (entries.getJSONObject(i).getString("key").startsWith("m:"))
            entries.getJSONObject(i).getJSONObject("data").getJSONArray("progressHistory").getJSONObject(0).put("to", 999);
        try { a.sync.apply(current, SyncDocument.parse(badJson)); fail("Invalid history accepted"); } catch (IllegalArgumentException expected) { }
        assertEquals(current.fingerprint(), a.sync.capture().fingerprint()); assertEquals(4, a.repo.progress(id).currentProgress, 0);
    }
    @Test public void backupRestorationPreservesIdentityAndIsANewRevision() throws Exception {
        Device a = new Device(), b = new Device(); a.add("Restore", 3); sync(a, b);
        String backup = a.repo.exportToJson(); assertEquals(6, new JSONObject(backup).getInt("version"));
        a.repo.incrementProgress(a.first(), 1); sync(a, b); a.repo.importFromJson(backup);
        sync(a, b); assertEquals(1, b.repo.media().size()); assertEquals(3, b.repo.progress(b.first()).currentProgress, 0);
        Device c = new Device(); c.repo.importFromJson(a.repo.exportToJson()); sync(a, c); assertEquals(1, c.repo.media().size());
    }
    @Test public void interruptionAfterOneCommitConvergesOnRetryWithoutRepeatedHistory() throws Exception {
        Device a = new Device(), b = new Device(); a.add("Retry", 2); sync(a, b);
        a.repo.incrementProgress(a.first(), 1); b.repo.updateTracking(b.first(), "reading", null, "Offline edit");
        SyncDocument left = a.sync.capture(), right = b.sync.capture();
        SyncDocument merged = resolve(left, right, a.sync.actor(), 0, 0); a.sync.apply(left, merged); // cable/radio lost before second commit
        sync(a, b); sync(b, a);
        assertEquals(1, b.repo.media().size()); assertEquals(1, b.db.progressHistoryDao().forBackup(b.first()).size());
        assertEquals(3, b.repo.progress(b.first()).currentProgress, 0);
    }
    @Test public void taxonomyConflictsAndRequiredCategoriesAreSafe() throws Exception {
        Device a = new Device(), b = new Device(); long id = a.add("Tagged", 0);
        GenreEntity genre = new GenreEntity(); genre.name = "Action"; genre.color = "#112233"; long gid = a.repo.addGenre(genre);
        a.repo.saveMedia(a.repo.media(id), a.repo.progress(id), Collections.singletonList(gid)); sync(a, b);
        GenreEntity remote = b.repo.findGenre("Action"); remote.color = "#223344"; b.repo.updateGenre(remote);
        genre = a.repo.findGenre("Action"); genre.color = "#334455"; a.repo.updateGenre(genre);
        assertEquals(1, new SyncDocument.Plan(a.sync.capture(), b.sync.capture(), a.sync.actor()).conflicts.size());
        sync(a, b, 1, 0); assertEquals("#223344", a.repo.findGenre("Action").color);
        a.repo.deleteGenre(a.repo.findGenre("Action").id); b.repo.incrementProgress(b.first(), 1);
        SyncDocument.Plan plan = new SyncDocument.Plan(a.sync.capture(), b.sync.capture(), a.sync.actor());
        while (!plan.conflicts.isEmpty()) { SyncDocument.Conflict conflict = plan.conflicts.get(0); plan.choose(conflict, conflict.here.key.startsWith("m:") ? 1 : 0); }
        SyncDocument merged = plan.finish(); assertFalse(plan.notices.isEmpty());
        a.sync.apply(a.sync.capture(), merged); b.sync.apply(b.sync.capture(), merged);
        assertEquals(1, a.repo.genresFor(id).size());
    }
    @Test public void wireRejectsCorruptionTruncationUnknownStepAndUnsafeLengths() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); SyncWire writer = new SyncWire(new ByteArrayInputStream(new byte[0]), out);
        writer.send("hello", new JSONObject().put("test", "data")); byte[] valid = out.toByteArray();
        assertEquals("data", new SyncWire(new ByteArrayInputStream(valid), new ByteArrayOutputStream()).receive("hello").getString("test"));
        for (int cut : new int[] {0, 3, 7, 20, valid.length - 1}) {
            try { new SyncWire(new ByteArrayInputStream(Arrays.copyOf(valid, cut)), new ByteArrayOutputStream()).receive("hello"); fail("Partial frame accepted"); } catch (IOException expected) { }
        }
        byte[] corrupt = valid.clone(); corrupt[corrupt.length - 2] ^= 42;
        try { new SyncWire(new ByteArrayInputStream(corrupt), out).receive("hello"); fail("Checksum ignored"); } catch (IOException expected) { }
        try { new SyncWire(new ByteArrayInputStream(valid), out).receive("commit"); fail("Out of order step accepted"); } catch (IOException expected) { }
        byte[] huge = valid.clone(); huge[4] = 0x7f;
        try { new SyncWire(new ByteArrayInputStream(huge), out).receive("hello"); fail("Unsafe allocation accepted"); } catch (IOException expected) { }
    }
    @Test public void thumbnailsArePortableAndOwningDeviceKeepsOriginalPhotos() throws Exception {
        Device a = new Device(true), b = new Device(true); CoverStore covers = CoverStore.get(context);
        String original = "content://watlis-sync-test/" + UUID.randomUUID(), characterOriginal = original + "/character";
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(32, 48, android.graphics.Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream(); bitmap.eraseColor(android.graphics.Color.RED);
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, encoded); bitmap.recycle();
        String thumbnail = android.util.Base64.encodeToString(encoded.toByteArray(), android.util.Base64.NO_WRAP);
        Set<String> sources = new HashSet<>(Arrays.asList(original, characterOriginal));
        try {
            covers.importThumbnail(original, thumbnail); covers.importThumbnail(characterOriginal, thumbnail);
            long id = a.add("Images", 5); MediaEntity m = a.repo.media(id); m.coverImage = original; m.coverZoom = 1.5f; m.coverPositionY = .75f;
            a.repo.saveMedia(m, a.repo.progress(id), Collections.emptyList());
            CharacterEntity c = new CharacterEntity(); c.mediaId = id; c.name = "Image fixture"; c.image = characterOriginal; c.description = "Keep this"; a.repo.saveCharacter(c);
            sync(a, b); String received = b.repo.media(b.first()).coverImage; sources.add(received);
            sources.add(b.repo.characters(b.first()).get(0).image);
            assertTrue(received.startsWith("watlis-sync://image/")); assertEquals(thumbnail, covers.exportThumbnail(received));
            assertEquals(original, a.repo.media(id).coverImage); assertEquals(characterOriginal, a.repo.characters(id).get(0).image);
            assertEquals(1.5f, b.repo.media(b.first()).coverZoom, 0); assertEquals(.75f, b.repo.media(b.first()).coverPositionY, 0);
            assertEquals("Keep this", b.repo.characters(b.first()).get(0).description);
            sync(b, a); assertEquals(original, a.repo.media(id).coverImage);
        } finally { for (String source : sources) java.nio.file.Files.deleteIfExists(covers.fileFor(source).toPath()); }
    }
    @Test public void identitySurvivesDatabaseReopenAndFailedApplyPreservesMetadata() throws Exception {
        String name = "watlis-sync-persistence-test-" + UUID.randomUUID() + ".db"; WatlisDatabase disk = null;
        try {
            disk = Room.databaseBuilder(context, WatlisDatabase.class, name).build(); WatlisRepository repo = new WatlisRepository(disk); repo.refresh();
            SyncEngine engine = new SyncEngine(disk, repo, "persisted-test-device"); MediaEntity m = new MediaEntity(); m.title = "Persist";
            repo.saveMedia(m, new UserProgressEntity(), Collections.emptyList()); String fingerprint = engine.capture().fingerprint(); disk.close();
            disk = Room.databaseBuilder(context, WatlisDatabase.class, name).build(); repo = new WatlisRepository(disk); engine = new SyncEngine(disk, repo, "persisted-test-device");
            assertEquals(fingerprint, engine.capture().fingerprint());
            disk.getOpenHelper().getWritableDatabase().execSQL("CREATE TRIGGER reject_sync BEFORE INSERT ON sync_state BEGIN SELECT RAISE(ABORT,'fixture'); END");
            try { engine.capture(); fail("Expected trigger failure"); } catch (android.database.sqlite.SQLiteException expected) { }
            disk.getOpenHelper().getWritableDatabase().execSQL("DROP TRIGGER reject_sync"); assertEquals(fingerprint, engine.capture().fingerprint());
        } finally { if (disk != null) disk.close(); context.deleteDatabase(name); }
    }
    @Test public void recoveryBackupKeepsPreviousVersionAndNoOpRetryDoesNotOverwriteIt() throws Exception {
        Device a = new Device(), b = new Device(); long id = a.add("Recoverable", 4); sync(a, b);
        File folder = new File(context.getCacheDir(), "sync-recovery-test-" + UUID.randomUUID());
        SyncEngine persistent = new SyncEngine(a.db, a.repo, folder, folder);
        try {
            b.repo.incrementProgress(b.first(), 1); SyncDocument left = persistent.capture(), right = b.sync.capture();
            SyncDocument merged = resolve(left, right, persistent.actor(), 0, 0); persistent.apply(left, merged); b.sync.apply(right, merged);
            String backup = new String(java.nio.file.Files.readAllBytes(persistent.recoveryFile.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(4, new JSONObject(backup).getJSONArray("media").getJSONObject(0).getJSONObject("progress").getDouble("currentProgress"), 0);
            assertTrue(new JSONObject(backup).has("syncState"));
            persistent.apply(persistent.capture(), b.sync.capture());
            assertEquals(backup, new String(java.nio.file.Files.readAllBytes(persistent.recoveryFile.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            JSONObject invalid = b.sync.capture().json();
            JSONArray records = invalid.getJSONArray("records");
            for (int i = 0; i < records.length(); i++) if (records.getJSONObject(i).getString("key").startsWith("m:"))
                records.getJSONObject(i).getJSONObject("data").getJSONObject("progress").put("currentProgress", 700);
            try { persistent.apply(persistent.capture(), SyncDocument.parse(invalid)); fail("Mismatched history accepted"); } catch (IllegalArgumentException expected) { }
            assertEquals(5, a.repo.progress(id).currentProgress, 0);
            assertEquals(backup, new String(java.nio.file.Files.readAllBytes(persistent.recoveryFile.toPath()), java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            for (String file : new String[]{"sync-device-id", "sync-device-id.bak", "sync-device-id.new", "before-bluetooth-sync.json", "before-bluetooth-sync.json.bak", "before-bluetooth-sync.json.new"})
                java.nio.file.Files.deleteIfExists(new File(folder, file).toPath());
            java.nio.file.Files.deleteIfExists(folder.toPath());
        }
    }
    @Test public void completeDuplexProtocolRequiresBothApprovals() throws Exception {
        Device a = new Device(), b = new Device(); a.add("Duplex phone", 1); b.add("Duplex tablet", 2);
        duplex(a, b, true); assertEquals(2, a.repo.media().size()); assertEquals(a.sync.capture().fingerprint(), b.sync.capture().fingerprint());
        a.repo.incrementProgress(a.first(), 1); String before = b.sync.capture().fingerprint();
        duplex(a, b, false); assertEquals(before, b.sync.capture().fingerprint());
    }
    private void duplex(Device a, Device b, boolean approve) throws Exception {
        PipedInputStream leftIn = new PipedInputStream(65536), rightIn = new PipedInputStream(65536);
        PipedOutputStream leftOut = new PipedOutputStream(rightIn), rightOut = new PipedOutputStream(leftIn);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        BluetoothSyncSession.Review review = new BluetoothSyncSession.Review() {
            @Override public SyncDocument resolve(SyncDocument local, SyncDocument remote) throws Exception { return BluetoothSyncInstrumentedTest.this.resolve(local, remote, a.sync.actor(), 0, 0); }
            @Override public boolean approve(SyncDocument local, SyncDocument remote, SyncDocument merged, boolean receiving) { return !receiving || approve; }
            @Override public void status(String status) { }
        };
        Future<?> left = pool.submit(() -> {
            try (InputStream in = leftIn; OutputStream out = leftOut) { BluetoothSyncSession.exchange(new SyncWire(in, out), a.sync, review, false, () -> {}, () -> {}, () -> {}); }
            catch (Exception e) { if (approve) throw new RuntimeException(e); }
        });
        Future<?> right = pool.submit(() -> {
            try (InputStream in = rightIn; OutputStream out = rightOut) { BluetoothSyncSession.exchange(new SyncWire(in, out), b.sync, review, true, () -> {}, () -> {}, () -> {}); }
            catch (Exception e) { if (approve) throw new RuntimeException(e); }
        });
        try { left.get(30, TimeUnit.SECONDS); right.get(30, TimeUnit.SECONDS); }
        finally { pool.shutdownNow(); leftIn.close(); rightIn.close(); leftOut.close(); rightOut.close(); }
    }
}
