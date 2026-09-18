package com.watlis.app.data;

import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** Worker-thread only. Revisions are captured on demand, so boot/list/progress taps stay lightweight. */
public final class SyncEngine {
    private final WatlisDatabase db;
    private final WatlisRepository repo;
    private final File identityFile;
    public final File recoveryFile;
    private String actor;

    public SyncEngine(WatlisDatabase db, WatlisRepository repo, File noBackupDirectory, File filesDirectory) {
        this.db = db; this.repo = repo;
        identityFile = new File(noBackupDirectory, "sync-device-id");
        recoveryFile = new File(filesDirectory, "before-bluetooth-sync.json");
        repo.syncEngine = this;
    }
    /** Independent identities for isolated tests, without touching the installed collection. */
    public SyncEngine(WatlisDatabase db, WatlisRepository repo, String actor) {
        this.db = db; this.repo = repo; this.actor = actor; identityFile = null; recoveryFile = null; repo.syncEngine = this;
    }
    public synchronized String actor() {
        if (actor != null) return actor;
        synchronized (SyncEngine.class) { try {
            AtomicFile file = new AtomicFile(identityFile);
            if (file.getBaseFile().exists()) actor = UUID.fromString(new String(file.readFully(), StandardCharsets.UTF_8)).toString();
            else { actor = UUID.randomUUID().toString(); atomicWrite(identityFile, actor); }
            return actor;
        } catch (Exception e) { throw new IllegalStateException("Cannot read this device's sync identity.", e); } }
    }
    public SyncDocument capture() {
        return db.runInTransaction(() -> {
            SyncDocument doc = capture(repo.exportForSync());
            if (doc.records.size() > SyncDocument.MAX_RECORDS) throw new IllegalArgumentException("Collection exceeds the Bluetooth sync record limit. Use Export / Import data instead.");
            return doc;
        });
    }

    private SyncDocument capture(JSONObject root) throws JSONException {
        Map<String, SyncStateEntity> states = states();
        Map<String, String> localMedia = new HashMap<>();
        for (SyncStateEntity s : states.values()) if (s.key.startsWith("m:") && s.localKey != null && !s.deleted) localMedia.put(s.localKey, s.key);
        SyncDocument doc = new SyncDocument();
        Map<String, String> types = new HashMap<>(); Map<Long, String> genres = new HashMap<>();
        JSONArray array = root.getJSONArray("mediaTypes");
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = SyncDocument.copy(array.getJSONObject(i)); String local = item.getString("key"); item.remove("key");
            String key = SyncDocument.catalogKey("t", item.getString("name")); types.put(local, key);
            captureRecord(doc, states, key, local, item.getString("name"), item);
        }
        array = root.getJSONArray("genres");
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = SyncDocument.copy(array.getJSONObject(i)); long local = item.getLong("id"); item.remove("id");
            String key = SyncDocument.catalogKey("g", item.getString("name")); genres.put(local, key);
            captureRecord(doc, states, key, Long.toString(local), item.getString("name"), item);
        }
        array = root.getJSONArray("media");
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = SyncDocument.copy(array.getJSONObject(i)); String local = Long.toString(item.getLong("id")); item.remove("id");
            String key = localMedia.get(local); if (key == null) key = "m:" + UUID.randomUUID();
            item.put("type", types.get(item.getString("type")));
            TreeSet<String> tags = new TreeSet<>(); JSONArray oldTags = item.getJSONArray("genreIds");
            for (int j = 0; j < oldTags.length(); j++) tags.add(genres.get(oldTags.getLong(j)));
            item.put("genreIds", new JSONArray(tags)); normalizeImage(item, "coverImage", "coverThumbnail");
            JSONArray chars = item.getJSONArray("characters");
            for (int j = 0; j < chars.length(); j++) { JSONObject c = chars.getJSONObject(j); c.remove("id"); normalizeImage(c, "image", "imageThumbnail"); }
            JSONArray history = item.getJSONArray("progressHistory");
            for (int j = 0; j < history.length(); j++) {
                JSONObject change = history.getJSONObject(j); change.remove("id");
                change.put("token", wireToken(key, change.getString("token")));
                if (change.has("undoOf")) change.put("undoOf", wireToken(key, change.getString("undoOf")));
            }
            captureRecord(doc, states, key, local, item.getString("title"), item);
        }
        for (SyncStateEntity state : states.values()) if (!doc.records.containsKey(state.key)) {
            TreeMap<String, Long> clock = SyncDocument.readClock(new JSONObject(state.clock));
            if (!state.deleted) SyncDocument.tick(clock, actor());
            state.deleted = true; state.localKey = null; state.hash = SyncDocument.digest("null"); state.clock = new JSONObject(clock).toString();
            db.syncStateDao().save(state);
            doc.records.put(state.key, new SyncDocument.Record(state.key, state.label, null, clock));
        }
        return doc;
    }
    private void captureRecord(SyncDocument doc, Map<String, SyncStateEntity> states, String key, String local, String label, JSONObject data) throws JSONException {
        SyncStateEntity state = states.get(key);
        TreeMap<String, Long> clock = state == null ? new TreeMap<>() : SyncDocument.readClock(new JSONObject(state.clock));
        String hash = SyncDocument.digest(SyncDocument.canonical(data));
        if (state == null || state.deleted || !state.hash.equals(hash)) SyncDocument.tick(clock, actor());
        SyncDocument.Record record = new SyncDocument.Record(key, label, data, clock);
        save(record, local); doc.records.put(key, record);
    }
    private void normalizeImage(JSONObject object, String field, String thumbnail) throws JSONException {
        String source = object.optString(field, "");
        if (source.isEmpty()) { object.remove(field); object.remove(thumbnail); return; }
        if (source.startsWith("watlis-sync://image/") || isWebImage(source)) return;
        String encoded = object.optString(thumbnail, "");
        object.put(field, "watlis-sync://image/" + SyncDocument.digest(encoded.isEmpty() ? actor() + "\n" + source : encoded));
    }
    private static boolean isWebImage(String source) { return source.startsWith("https://") || source.startsWith("http://"); }
    private Map<String, SyncStateEntity> states() {
        Map<String, SyncStateEntity> states = new TreeMap<>(); for (SyncStateEntity s : db.syncStateDao().all()) states.put(s.key, s); return states;
    }
    private void save(SyncDocument.Record r, String local) {
        SyncStateEntity s = new SyncStateEntity(); s.key = r.key; s.localKey = r.deleted() ? null : local;
        s.hash = r.hash(); s.clock = new JSONObject(r.clock).toString(); s.label = r.label; s.deleted = r.deleted(); db.syncStateDao().save(s);
    }

    /** Called from the ordinary export transaction, preserving identities even before the first Bluetooth sync. */
    void decorateBackup(JSONObject root) throws JSONException {
        capture(root); JSONArray array = new JSONArray();
        for (SyncStateEntity s : db.syncStateDao().all()) array.put(new JSONObject().put("key", s.key).put("localKey", s.localKey)
                .put("hash", s.hash).put("clock", new JSONObject(s.clock)).put("label", s.label).put("deleted", s.deleted));
        root.put("syncState", array); root.put("version", 6);
    }
    /** A restore is a new local revision, never a rewind of causal knowledge. Runs inside the restore transaction. */
    void restoredBackup(JSONObject root) throws JSONException {
        Map<String, SyncStateEntity> old = states(); db.syncStateDao().clear();
        Set<String> used = new HashSet<>(), localIds = new HashSet<>(); JSONArray metadata = root.optJSONArray("syncState");
        if (metadata != null) {
            for (int i = 0; i < metadata.length(); i++) {
                JSONObject v = metadata.getJSONObject(i); SyncStateEntity s = new SyncStateEntity();
                s.key = v.getString("key"); s.localKey = v.optString("localKey", null); s.hash = v.getString("hash");
                s.label = v.getString("label"); s.deleted = v.getBoolean("deleted");
                if (!(s.key.startsWith("m:") || s.key.startsWith("g:") || s.key.startsWith("t:")) || !used.add(s.key)
                        || !s.deleted && (s.localKey == null || !localIds.add(s.key.substring(0, 2) + s.localKey)))
                    throw new IllegalArgumentException("Invalid sync identities in backup.");
                TreeMap<String, Long> clock = SyncDocument.readClock(v.getJSONObject("clock"));
                if (old.containsKey(s.key)) clock = SyncDocument.join(clock, SyncDocument.readClock(new JSONObject(old.get(s.key).clock)));
                SyncDocument.tick(clock, actor()); s.clock = new JSONObject(clock).toString(); db.syncStateDao().save(s);
            }
        }
        for (SyncStateEntity s : old.values()) if (!used.contains(s.key)) {
            TreeMap<String, Long> clock = SyncDocument.readClock(new JSONObject(s.clock)); SyncDocument.tick(clock, actor());
            s.deleted = true; s.localKey = null; s.hash = SyncDocument.digest("null"); s.clock = new JSONObject(clock).toString(); db.syncStateDao().save(s);
        }
        capture(repo.exportForSync(false));
    }

    public void assertCurrent(SyncDocument expected) {
        db.runInTransaction(() -> {
            if (!capture(repo.exportForSync()).fingerprint().equals(expected.fingerprint()))
                throw new IllegalStateException("Your collection changed during review. Start sync again; nothing was overwritten.");
            return null;
        });
    }
    /** Reject fabricated proposal values. Every surviving value must have been reviewed from one of these snapshots. */
    public static void validateProposal(SyncDocument here, SyncDocument there, SyncDocument merged) {
        Set<String> keys = new HashSet<>(here.records.keySet()); keys.addAll(there.records.keySet());
        if (!keys.equals(merged.records.keySet())) throw new IllegalArgumentException("Incomplete sync proposal.");
        Set<String> knownValues = new HashSet<>();
        for (SyncDocument.Record r : here.records.values()) knownValues.add(r.hash());
        for (SyncDocument.Record r : there.records.values()) knownValues.add(r.hash());
        for (SyncDocument.Record r : merged.records.values()) {
            SyncDocument.Record a = here.records.get(r.key), b = there.records.get(r.key);
            if (a != null && !SyncDocument.dominates(r.clock, a.clock) || b != null && !SyncDocument.dominates(r.clock, b.clock)
                    || !r.deleted() && !knownValues.contains(r.hash())) throw new IllegalArgumentException("Invalid sync proposal revision.");
        }
    }
    public void apply(SyncDocument expected, SyncDocument merged) {
        try {
            db.runInTransaction(() -> {
                JSONObject before = repo.exportForSync(); SyncDocument current = capture(before);
                if (!current.fingerprint().equals(expected.fingerprint()))
                    throw new IllegalStateException("Your collection changed during review. Sync again; nothing was overwritten.");
                JSONObject backup = materialize(merged, before, current);
                boolean changed = contentChanged(current, merged);
                if (changed && recoveryFile != null) decorateBackup(before);
                if (changed) repo.importFromJson(backup.toString(), false);
                Map<String, String> localKeys = new HashMap<>();
                JSONArray mapping = backup.getJSONArray("syncMapping");
                for (int i = 0; i < mapping.length(); i++) { JSONObject m = mapping.getJSONObject(i); localKeys.put(m.getString("key"), m.getString("local")); }
                db.syncStateDao().clear();
                for (SyncDocument.Record r : merged.records.values()) save(r, localKeys.get(r.key));
                // Detect representation/validation mistakes inside the transaction, before committing anything.
                SyncDocument after = capture(repo.exportForSync());
                if (!after.fingerprint().equals(merged.fingerprint())) throw new IllegalStateException("Sync verification failed; all database changes were rolled back.");
                // Write only a validated recovery point, still before the database transaction commits.
                if (changed && recoveryFile != null) atomicWrite(recoveryFile, before.toString());
                return null;
            });
        } finally { repo.refresh(); }
    }
    public static boolean contentChanged(SyncDocument a, SyncDocument b) {
        for (SyncDocument.Record r : b.records.values()) {
            SyncDocument.Record old = a.records.get(r.key);
            if (old == null ? !r.deleted() : !r.hash().equals(old.hash())) return true;
        }
        return false;
    }

    private JSONObject materialize(SyncDocument merged, JSONObject oldRoot, SyncDocument current) throws JSONException {
        Map<String, SyncStateEntity> states = states();
        Map<Long, JSONObject> oldMedia = new HashMap<>(); long nextMedia = nextRowId("media"), nextGenre = nextRowId("genres");
        JSONArray array = oldRoot.getJSONArray("media");
        for (int i = 0; i < array.length(); i++) { JSONObject m = array.getJSONObject(i); oldMedia.put(m.getLong("id"), m); nextMedia = Math.max(nextMedia, m.getLong("id") + 1); }
        Map<String, String> originals = new HashMap<>();
        for (SyncDocument.Record r : current.records.values()) if (r.key.startsWith("m:") && !r.deleted()) {
            SyncStateEntity state = states.get(r.key);
            JSONObject raw = state == null || state.localKey == null ? null : oldMedia.get(Long.parseLong(state.localKey));
            if (raw == null) continue;
            rememberOriginal(originals, raw, r.data, "coverImage");
            JSONArray rawChars = raw.getJSONArray("characters"), normalizedChars = r.data.getJSONArray("characters");
            for (int i = 0; i < rawChars.length(); i++) rememberOriginal(originals, rawChars.getJSONObject(i), normalizedChars.getJSONObject(i), "image");
        }
        for (GenreEntity g : db.genreDao().getAll()) nextGenre = Math.max(nextGenre, g.id + 1);
        Map<String, String> typeIds = new HashMap<>(); Map<String, Long> genreIds = new HashMap<>();
        JSONArray types = new JSONArray(), genres = new JSONArray(), media = new JSONArray(), mapping = new JSONArray();
        for (SyncDocument.Record r : merged.records.values()) if (!r.deleted() && !r.key.startsWith("m:")) {
            JSONObject value = SyncDocument.copy(r.data); String local;
            if (r.key.startsWith("t:")) {
                MediaTypeEntity existing = db.mediaTypeDao().findByName(value.getString("name"));
                local = existing == null ? "sync_" + SyncDocument.digest(r.key) : existing.key;
                value.put("key", local); types.put(value); typeIds.put(r.key, local);
            } else {
                GenreEntity existing = db.genreDao().findByName(value.getString("name")); long id = existing == null ? nextGenre++ : existing.id;
                if (!value.getString("color").matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Invalid genre color in sync.");
                value.put("id", id); genres.put(value); genreIds.put(r.key, id); local = Long.toString(id);
            }
            mapping.put(new JSONObject().put("key", r.key).put("local", local));
        }
        if (types.length() == 0) throw new IllegalArgumentException("Keep at least one media type when resolving sync.");
        long nextCharacter = 1, nextHistory = 1;
        for (SyncDocument.Record r : merged.records.values()) if (r.key.startsWith("m:") && !r.deleted()) {
            SyncStateEntity state = states.get(r.key);
            long id = state != null && state.localKey != null && oldMedia.containsKey(Long.parseLong(state.localKey)) ? Long.parseLong(state.localKey) : nextMedia++;
            JSONObject value = SyncDocument.copy(r.data); value.put("id", id);
            String type = typeIds.get(value.getString("type")); if (type == null) throw new IllegalArgumentException("A synced media type is missing."); value.put("type", type);
            JSONArray refs = value.getJSONArray("genreIds"), mapped = new JSONArray();
            for (int i = 0; i < refs.length(); i++) { Long g = genreIds.get(refs.getString(i)); if (g == null) throw new IllegalArgumentException("A synced genre is missing."); mapped.put(g); }
            value.put("genreIds", mapped);
            restoreLocalImage(value, "coverImage", originals);
            JSONArray chars = value.getJSONArray("characters");
            for (int i = 0; i < chars.length(); i++) {
                JSONObject c = chars.getJSONObject(i); c.put("id", nextCharacter++);
                restoreLocalImage(c, "image", originals);
            }
            JSONArray history = value.getJSONArray("progressHistory");
            for (int i = 0; i < history.length(); i++) {
                JSONObject change = history.getJSONObject(i); change.put("id", nextHistory++);
                // Legacy backup copies can share event tokens. Scope storage tokens to stable title identity,
                // retaining the original wire token and the existing database's global uniqueness guarantee.
                change.put("token", tokenPrefix(r.key) + change.getString("token"));
                if (change.has("undoOf")) change.put("undoOf", tokenPrefix(r.key) + change.getString("undoOf"));
            }
            double x = value.getDouble("coverPositionX"), y = value.getDouble("coverPositionY");
            if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || x > 1 || y < 0 || y > 1) throw new IllegalArgumentException("Invalid synced cover position.");
            JSONObject progress = value.getJSONObject("progress");
            if (progress.has("rating") && (progress.getInt("rating") < 1 || progress.getInt("rating") > 10)) throw new IllegalArgumentException("Invalid synced rating.");
            media.put(value); mapping.put(new JSONObject().put("key", r.key).put("local", Long.toString(id)));
        }
        return new JSONObject().put("version", 6).put("mediaTypes", types).put("genres", genres).put("media", media).put("syncMapping", mapping);
    }
    private static void rememberOriginal(Map<String, String> originals, JSONObject raw, JSONObject normalized, String field) {
        String source = raw.optString(field, ""), token = normalized.optString(field, "");
        if (!token.isEmpty() && !source.isEmpty() && !source.startsWith("watlis-sync://")) originals.put(token, source);
    }
    private static String tokenPrefix(String key) { return "watlis-sync/" + key + "/"; }
    private static String wireToken(String key, String token) {
        String prefix = tokenPrefix(key); return token.startsWith(prefix) ? token.substring(prefix.length()) : token;
    }
    private long nextRowId(String table) {
        try (android.database.Cursor cursor = db.getOpenHelper().getReadableDatabase().query("SELECT seq FROM sqlite_sequence WHERE name=?", new Object[]{table})) {
            return cursor.moveToFirst() ? Math.addExact(cursor.getLong(0), 1) : 1;
        }
    }
    private static void restoreLocalImage(JSONObject value, String field, Map<String, String> originals) throws JSONException {
        String image = value.optString(field, "");
        if (!image.isEmpty() && !isWebImage(image) && !image.matches("watlis-sync://image/[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid image reference in sync data.");
        // Keep the high-quality, local original on its owning device; only portable thumbnails cross Bluetooth.
        if (originals.containsKey(image)) value.put(field, originals.get(image));
    }
    private static void atomicWrite(File target, String text) throws java.io.IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new java.io.IOException("Cannot create sync storage.");
        AtomicFile file = new AtomicFile(target); FileOutputStream out = null;
        try { out = file.startWrite(); out.write(text.getBytes(StandardCharsets.UTF_8)); file.finishWrite(out); out = null; }
        finally { if (out != null) file.failWrite(out); }
    }
}
