package com.watlis.app.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Room work runs on the ViewModel executor; UI reads the last complete local snapshot. */
public class WatlisRepository {
    private final WatlisDatabase db;
    private final com.watlis.app.CoverStore coverStore;
    private volatile Snapshot snapshot = new Snapshot();

    private static class Snapshot {
        List<MediaEntity> media = new ArrayList<>();
        List<GenreEntity> genres = new ArrayList<>();
        List<MediaTypeEntity> types = new ArrayList<>();
        Map<Long, UserProgressEntity> progress = new HashMap<>();
        Map<Long, List<GenreEntity>> tags = new HashMap<>();
        Map<Long, StoryMemoryEntity> stories = new HashMap<>();
        Map<Long, List<CharacterEntity>> characters = new HashMap<>();
    }

    public WatlisRepository(WatlisDatabase db) { this(db, null); }
    public WatlisRepository(WatlisDatabase db, com.watlis.app.CoverStore coverStore) { this.db = db; this.coverStore = coverStore; }

    public void refresh() {
        Snapshot next = new Snapshot();
        db.runInTransaction(() -> {
            ensureMediaTypes();
            next.types = db.mediaTypeDao().getAll();
            next.media = db.mediaDao().getAll();
            next.genres = db.genreDao().getAll();
            for (UserProgressEntity p : db.progressDao().getAll()) next.progress.put(p.mediaId, p);
            for (StoryMemoryEntity s : db.storyDao().getAll()) next.stories.put(s.mediaId, s);
            for (CharacterEntity c : db.storyDao().allCharacters())
                next.characters.computeIfAbsent(c.mediaId, k -> new ArrayList<>()).add(c);
            Map<Long, GenreEntity> byId = new HashMap<>();
            for (GenreEntity g : next.genres) byId.put(g.id, g);
            for (MediaGenreCrossRef link : db.genreDao().allLinks()) {
                GenreEntity genre = byId.get(link.genreId);
                if (genre != null) next.tags.computeIfAbsent(link.mediaId, k -> new ArrayList<>()).add(genre);
            }
            for (List<GenreEntity> tags : next.tags.values())
                tags.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name));
        });
        snapshot = next;
    }

    public List<MediaEntity> media() { return new ArrayList<>(snapshot.media); }
    public List<MediaTypeEntity> mediaTypes() { return new ArrayList<>(snapshot.types); }
    public MediaTypeEntity mediaType(String key) {
        for (MediaTypeEntity type : snapshot.types) if (type.key.equals(key)) return type;
        return null;
    }
    private void ensureMediaTypes() {
        if (!db.mediaTypeDao().getAll().isEmpty()) return;
        for (String key : new String[]{"manga", "manhwa", "manhua", "anime"}) {
            MediaTypeEntity type = new MediaTypeEntity();
            type.key = key;
            type.name = Character.toUpperCase(key.charAt(0)) + key.substring(1);
            type.usesEpisodes = key.equals("anime");
            db.mediaTypeDao().insert(type);
        }
    }
    public void saveMediaType(MediaTypeEntity type) {
        db.runInTransaction(() -> {
            ensureMediaTypes();
            type.name = type.name.trim();
            if (type.name.isEmpty()) throw new IllegalArgumentException("Media type name is required");
            MediaTypeEntity duplicate = db.mediaTypeDao().findByName(type.name);
            if (duplicate != null && !duplicate.key.equals(type.key))
                throw new IllegalArgumentException("Media type names must be unique");
            if (type.key.isEmpty()) {
                type.key = "custom_" + java.util.UUID.randomUUID();
                db.mediaTypeDao().insert(type);
            } else {
                if (db.mediaTypeDao().get(type.key) == null) throw new IllegalArgumentException("Media type no longer exists");
                db.mediaTypeDao().update(type);
            }
        });
        refresh();
    }
    public void deleteMediaType(String key, String replacement) {
        db.runInTransaction(() -> {
            if (db.mediaTypeDao().get(key) == null) return;
            if (db.mediaTypeDao().getAll().size() <= 1) throw new IllegalArgumentException("Keep at least one media type");
            if (db.mediaTypeDao().usage(key) > 0) {
                if (key.equals(replacement) || replacement == null || db.mediaTypeDao().get(replacement) == null)
                    throw new IllegalArgumentException("Choose a replacement for existing titles");
                db.mediaTypeDao().reassign(key, replacement, System.currentTimeMillis());
            }
            db.mediaTypeDao().delete(key);
        });
        refresh();
    }
    public MediaEntity media(long id) {
        for (MediaEntity m : snapshot.media) if (m.id == id) return m;
        return null;
    }
    public UserProgressEntity progress(long id) {
        UserProgressEntity p = snapshot.progress.get(id);
        if (p != null) return p;
        p = new UserProgressEntity();
        p.mediaId = id;
        return p;
    }
    public List<GenreEntity> genres() { return new ArrayList<>(snapshot.genres); }
    public List<GenreEntity> genresFor(long id) {
        List<GenreEntity> list = snapshot.tags.get(id);
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }
    public StoryMemoryEntity story(long id) { return snapshot.stories.get(id); }
    public List<CharacterEntity> characters(long id) {
        List<CharacterEntity> list = snapshot.characters.get(id);
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    public long saveMedia(MediaEntity media, UserProgressEntity progress, List<Long> genreIds) {
        return saveMedia(media, progress, genreIds, change -> {});
    }

    public long saveMedia(MediaEntity media, UserProgressEntity progress, List<Long> genreIds,
                          java.util.function.Consumer<ProgressHistoryEntity> recorded) {
        return saveImportedMedia(media, progress, genreIds, java.util.Collections.emptyList(), null, recorded);
    }

    /** Missing imported taxonomy is created only on Save, in the same transaction as the media. */
    public long saveImportedMedia(MediaEntity media, UserProgressEntity progress, List<Long> genreIds,
                                  List<String> genreNames, String typeName,
                                  java.util.function.Consumer<ProgressHistoryEntity> recorded) {
        ProgressHistoryEntity[] change = {null};
        validateProgress(progress.currentProgress);
        if (!Float.isFinite(media.coverZoom) || media.coverZoom < 1 || media.coverZoom > 3)
            throw new IllegalArgumentException("Cover zoom must be between 1 and 3");
        if (!Float.isFinite(media.coverPositionX) || media.coverPositionX < 0 || media.coverPositionX > 1
                || !Float.isFinite(media.coverPositionY) || media.coverPositionY < 0 || media.coverPositionY > 1)
            throw new IllegalArgumentException("Cover position must be between 0 and 1");
        if (media.title.trim().isEmpty()) throw new IllegalArgumentException("Title is required");
        if (progress.rating != null && (progress.rating < 1 || progress.rating > 10))
            throw new IllegalArgumentException("Rating must be between 1 and 10");
        long originalId = media.id;
        String originalType = media.type;
        try { db.runInTransaction(() -> {
            ensureMediaTypes();
            if ("imported_type".equals(media.type) && typeName != null && !typeName.trim().isEmpty()) {
                MediaTypeEntity type = db.mediaTypeDao().findByName(typeName.trim());
                if (type == null) {
                    type = new MediaTypeEntity(); type.name = typeName.trim();
                    type.key = "custom_" + java.util.UUID.randomUUID();
                    db.mediaTypeDao().insert(type);
                }
                media.type = type.key;
            }
            if (db.mediaTypeDao().get(media.type) == null) throw new IllegalArgumentException("Choose an existing media type");
            UserProgressEntity previous = media.id == 0 ? null : db.progressDao().get(media.id);
            long now = System.currentTimeMillis();
            media.title = media.title.trim();
            media.updatedAt = now;
            if (media.createdAt == 0) media.createdAt = now;
            if (media.id == 0) media.id = db.mediaDao().insert(media);
            else db.mediaDao().update(media);
            progress.mediaId = media.id;
            progress.lastUpdatedAt = previous == null || previous.currentProgress != progress.currentProgress
                    ? now : previous.lastUpdatedAt;
            db.progressDao().save(progress);
            // Initial progress is a baseline, not a fabricated past reading session.
            if (previous != null && previous.currentProgress != progress.currentProgress)
                change[0] = recordProgress(previous, progress, "correction", null);
            db.genreDao().clearForMedia(media.id);
            for (Long genreId : genreIds) db.genreDao().addToMedia(new MediaGenreCrossRef(media.id, genreId));
            for (String name : genreNames) {
                if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("Genre name is required");
                GenreEntity genre = db.genreDao().findByName(name.trim());
                if (genre == null) {
                    genre = new GenreEntity(); genre.name = name.trim(); genre.color = "#9CBFFF";
                    genre.id = db.genreDao().insert(genre);
                }
                db.genreDao().addToMedia(new MediaGenreCrossRef(media.id, genre.id));
            }
        }); } catch (RuntimeException error) { media.id = originalId; media.type = originalType; throw error; }
        refresh();
        recorded.accept(change[0]);
        return media.id;
    }

    public static void validateProgress(double value) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Enter a valid nonnegative number");
    }

    public ProgressHistoryEntity updateProgress(long id, double amount) {
        validateProgress(amount);
        ProgressHistoryEntity change = db.runInTransaction(() -> writeProgress(id, amount, "correction"));
        refreshProgress(id);
        return change;
    }
    public ProgressHistoryEntity incrementProgress(long id, double delta) {
        if (!Double.isFinite(delta)) throw new IllegalArgumentException("Enter a valid change");
        ProgressHistoryEntity change = db.runInTransaction(() -> {
            UserProgressEntity current = db.progressDao().get(id);
            return current == null ? null : writeProgress(id, Math.max(0, current.currentProgress + delta), delta > 0 ? "reading" : "correction");
        });
        refreshProgress(id);
        return change;
    }
    private void refreshProgress(long id) {
        Snapshot previous = snapshot;
        Snapshot next = new Snapshot();
        next.media = previous.media;
        next.genres = previous.genres;
        next.types = previous.types;
        next.tags = previous.tags;
        next.stories = previous.stories;
        next.characters = previous.characters;
        next.progress = new HashMap<>(previous.progress);
        next.progress.put(id, db.progressDao().get(id));
        snapshot = next;
    }
    private ProgressHistoryEntity writeProgress(long id, double amount, String kind) {
        validateProgress(amount);
        UserProgressEntity p = db.progressDao().get(id);
        if (p == null || p.currentProgress == amount) return null;
        UserProgressEntity before = db.progressDao().get(id);
        p.currentProgress = amount;
        p.lastUpdatedAt = System.currentTimeMillis();
        db.progressDao().save(p);
        return recordProgress(before, p, kind, null);
    }

    private ProgressHistoryEntity recordProgress(UserProgressEntity before, UserProgressEntity after, String kind, String undoOf) {
        ProgressHistoryEntity change = new ProgressHistoryEntity();
        change.mediaId = after.mediaId;
        change.fromProgress = before.currentProgress; change.toProgress = after.currentProgress;
        change.beforeUpdatedAt = before.lastUpdatedAt; change.afterUpdatedAt = after.lastUpdatedAt;
        change.recordedAt = System.currentTimeMillis(); change.kind = kind; change.undoOf = undoOf;
        MediaEntity media = db.mediaDao().getById(after.mediaId);
        MediaTypeEntity type = media == null ? null : db.mediaTypeDao().get(media.type);
        change.unit = type != null && type.usesEpisodes ? "Episode" : "Chapter";
        change.id = db.progressHistoryDao().insert(change);
        return change;
    }

    /** Indexed keyset paging: history is never included in the startup snapshot. */
    public List<ProgressHistoryEntity> progressHistory(long mediaId, long beforeId, int limit) {
        return db.progressHistoryDao().page(mediaId, beforeId, Math.max(1, Math.min(50, limit)));
    }

    public boolean undoProgress(long mediaId, String expectedToken) {
        boolean undone = db.runInTransaction(() -> {
            ProgressHistoryEntity latest = db.progressHistoryDao().latest(mediaId);
            UserProgressEntity p = db.progressDao().get(mediaId);
            if (latest == null || p == null || "undo".equals(latest.kind) || !latest.token.equals(expectedToken)
                    || p.currentProgress != latest.toProgress || p.lastUpdatedAt != latest.afterUpdatedAt) return false;
            UserProgressEntity before = db.progressDao().get(mediaId);
            p.currentProgress = latest.fromProgress;
            p.lastUpdatedAt = latest.beforeUpdatedAt;
            db.progressDao().save(p);
            recordProgress(before, p, "undo", latest.token);
            return true;
        });
        refreshProgress(mediaId);
        return undone;
    }
    public void favorite(long id) {
        db.runInTransaction(() -> {
            MediaEntity m = db.mediaDao().getById(id);
            if (m != null) {
                m.isFavorite = !m.isFavorite;
                m.updatedAt = System.currentTimeMillis();
                db.mediaDao().update(m);
            }
        });
        refresh();
    }
    public void updateTracking(long id, String status, Integer rating, String notes) {
        if (rating != null && (rating < 1 || rating > 10)) throw new IllegalArgumentException("Rating must be between 1 and 10");
        db.runInTransaction(() -> {
            UserProgressEntity p = db.progressDao().get(id);
            if (p != null) {
                p.trackingStatus = status;
                p.rating = rating;
                p.notes = notes;
                db.progressDao().save(p);
            }
        });
        refresh();
    }
    public void saveStory(StoryMemoryEntity story) { db.storyDao().save(story); refresh(); }
    public void saveCharacter(CharacterEntity character) {
        if (character.name.trim().isEmpty()) throw new IllegalArgumentException("Character name is required");
        if (character.id == 0) db.storyDao().addCharacter(character); else db.storyDao().updateCharacter(character);
        refresh();
    }
    public void deleteCharacter(long id) { db.storyDao().deleteCharacter(id); refresh(); }
    public void deleteMedia(MediaEntity media) { db.mediaDao().delete(media); refresh(); }
    public GenreEntity findGenre(String name) {
        for (GenreEntity g : snapshot.genres) if (g.name.equalsIgnoreCase(name.trim())) return g;
        return null;
    }
    private void validateGenre(GenreEntity genre) {
        genre.name = genre.name.trim();
        if (genre.name.isEmpty()) throw new IllegalArgumentException("Genre name is required");
        GenreEntity match = db.genreDao().findByName(genre.name);
        if (match != null && match.id != genre.id) throw new IllegalArgumentException("Genre names must be unique");
        if (!genre.color.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Use a color such as #9CBFFF");
    }
    public long addGenre(GenreEntity genre) {
        validateGenre(genre);
        long id = db.genreDao().insert(genre);
        refresh();
        return id;
    }
    public void updateGenre(GenreEntity genre) { validateGenre(genre); db.genreDao().update(genre); refresh(); }
    public void deleteGenre(long id) { db.genreDao().delete(id); refresh(); }
    public int countStatus(String status) {
        int count = 0;
        for (UserProgressEntity p : snapshot.progress.values()) if (p != null && status.equals(p.trackingStatus)) count++;
        return count;
    }
    public Double averageRating() {
        int sum = 0, count = 0;
        for (UserProgressEntity p : snapshot.progress.values()) if (p != null && p.rating != null) { sum += p.rating; count++; }
        return count == 0 ? null : (double) sum / count;
    }
    public int favoriteCount() {
        int count = 0;
        for (MediaEntity m : snapshot.media) if (m.isFavorite) count++;
        return count;
    }

    public String exportToJson() {
        return db.runInTransaction(() -> {
            refresh();
            return exportSnapshotToJson();
        });
    }

    private String exportSnapshotToJson() {
        try {
            org.json.JSONObject root = new org.json.JSONObject();
            root.put("version", 5);
            org.json.JSONArray typeArray = new org.json.JSONArray();
            for (MediaTypeEntity type : snapshot.types) {
                org.json.JSONObject value = new org.json.JSONObject();
                value.put("key", type.key); value.put("name", type.name); value.put("usesEpisodes", type.usesEpisodes);
                typeArray.put(value);
            }
            root.put("mediaTypes", typeArray);
            root.put("exportedAt", System.currentTimeMillis());
            org.json.JSONArray genresArr = new org.json.JSONArray();
            for (GenreEntity g : snapshot.genres) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("id", g.id); o.put("name", g.name); o.put("color", g.color);
                genresArr.put(o);
            }
            root.put("genres", genresArr);
            org.json.JSONArray mediaArr = new org.json.JSONArray();
            for (MediaEntity m : snapshot.media) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("id", m.id); o.put("title", m.title); o.put("type", m.type);
                if (m.coverImage != null) o.put("coverImage", m.coverImage);
                o.put("coverPositionX", m.coverPositionX); o.put("coverPositionY", m.coverPositionY);
                o.put("coverZoom", m.coverZoom);
                if (coverStore != null) o.put("coverThumbnail", coverStore.exportThumbnail(m.coverImage));
                o.put("releaseStatus", m.releaseStatus);
                o.put("createdAt", m.createdAt); o.put("updatedAt", m.updatedAt);
                o.put("isFavorite", m.isFavorite);
                UserProgressEntity p = progress(m.id);
                org.json.JSONObject po = new org.json.JSONObject();
                po.put("currentProgress", p.currentProgress);
                if (p.rating != null) po.put("rating", (int) p.rating);
                po.put("trackingStatus", p.trackingStatus);
                if (p.notes != null) po.put("notes", p.notes);
                po.put("lastUpdatedAt", p.lastUpdatedAt);
                o.put("progress", po);
                org.json.JSONArray gIds = new org.json.JSONArray();
                for (GenreEntity g : genresFor(m.id)) gIds.put(g.id);
                o.put("genreIds", gIds);
                StoryMemoryEntity st = story(m.id);
                if (st != null) {
                    org.json.JSONObject so = new org.json.JSONObject();
                    if (st.mainCharacterName != null) so.put("mainCharacterName", st.mainCharacterName);
                    if (st.storySummary != null) so.put("storySummary", st.storySummary);
                    if (st.lastStoryPoint != null) so.put("lastStoryPoint", st.lastStoryPoint);
                    if (st.importantNotes != null) so.put("importantNotes", st.importantNotes);
                    o.put("storyMemory", so);
                }
                org.json.JSONArray chars = new org.json.JSONArray();
                for (CharacterEntity c : characters(m.id)) {
                    org.json.JSONObject co = new org.json.JSONObject();
                    co.put("id", c.id); co.put("name", c.name);
                    if (c.role != null) co.put("role", c.role);
                    if (c.description != null) co.put("description", c.description);
                    if (c.image != null) co.put("image", c.image);
                    if (coverStore != null) co.put("imageThumbnail", coverStore.exportThumbnail(c.image));
                    chars.put(co);
                }
                o.put("characters", chars);
                org.json.JSONArray history = new org.json.JSONArray();
                for (ProgressHistoryEntity change : db.progressHistoryDao().forBackup(m.id)) {
                    org.json.JSONObject entry = new org.json.JSONObject();
                    entry.put("id", change.id); entry.put("token", change.token);
                    entry.put("from", change.fromProgress); entry.put("to", change.toProgress);
                    entry.put("recordedAt", change.recordedAt); entry.put("beforeUpdatedAt", change.beforeUpdatedAt);
                    entry.put("afterUpdatedAt", change.afterUpdatedAt); entry.put("kind", change.kind);
                    entry.put("unit", change.unit); if (change.undoOf != null) entry.put("undoOf", change.undoOf);
                    history.put(entry);
                }
                o.put("progressHistory", history);
                mediaArr.put(o);
            }
            root.put("media", mediaArr);
            return root.toString(2);
        } catch (org.json.JSONException e) {
            throw new RuntimeException("Failed to serialize data", e);
        }
    }

    public void importFromJson(String json) {
        org.json.JSONObject root;
        try { root = new org.json.JSONObject(json); }
        catch (org.json.JSONException e) { throw new IllegalArgumentException("Invalid backup file. Select a Watlis export file."); }
        if (root.optInt("version", 0) < 1) throw new IllegalArgumentException("Unrecognized backup file format");
        db.runInTransaction(() -> {
            try {
                androidx.sqlite.db.SupportSQLiteDatabase sql = db.getOpenHelper().getWritableDatabase();
                sql.execSQL("DELETE FROM progress_history");
                sql.execSQL("DELETE FROM characters");
                sql.execSQL("DELETE FROM story_memory");
                sql.execSQL("DELETE FROM media_genres");
                sql.execSQL("DELETE FROM user_progress");
                sql.execSQL("DELETE FROM media");
                sql.execSQL("DELETE FROM genres");
                sql.execSQL("DELETE FROM media_types");
                org.json.JSONArray types = root.optJSONArray("mediaTypes");
                if (types != null) for (int i = 0; i < types.length(); i++) {
                    org.json.JSONObject value = types.getJSONObject(i);
                    MediaTypeEntity type = new MediaTypeEntity();
                    type.key = value.getString("key"); type.name = value.getString("name").trim();
                    type.usesEpisodes = value.optBoolean("usesEpisodes", false);
                    if (type.key.isEmpty() || type.name.isEmpty()) throw new IllegalArgumentException("Invalid media type in backup");
                    db.mediaTypeDao().insert(type);
                }
                ensureMediaTypes();
                org.json.JSONArray genres = root.getJSONArray("genres");
                for (int i = 0; i < genres.length(); i++) {
                    org.json.JSONObject o = genres.getJSONObject(i);
                    GenreEntity g = new GenreEntity();
                    g.id = o.getLong("id"); g.name = o.getString("name"); g.color = o.optString("color", "#B9F34A");
                    db.genreDao().insert(g);
                }
                org.json.JSONArray media = root.getJSONArray("media");
                for (int i = 0; i < media.length(); i++) {
                    org.json.JSONObject o = media.getJSONObject(i);
                    MediaEntity m = new MediaEntity();
                    m.id = o.getLong("id"); m.title = o.getString("title"); m.type = o.optString("type", "manga");
                    if (db.mediaTypeDao().get(m.type) == null) {
                        MediaTypeEntity type = new MediaTypeEntity();
                        type.key = m.type; type.name = m.type;
                        if (type.key.isEmpty()) throw new IllegalArgumentException("Invalid media type in backup");
                        db.mediaTypeDao().insert(type);
                    }
                    m.coverImage = nullStr(o, "coverImage");
                    m.coverPositionX = (float) o.optDouble("coverPositionX", 0.5);
                    m.coverPositionY = (float) o.optDouble("coverPositionY", 0.5);
                    m.coverZoom = (float) o.optDouble("coverZoom", 1);
                    if (!Float.isFinite(m.coverZoom) || m.coverZoom < 1 || m.coverZoom > 3)
                        throw new IllegalArgumentException("Invalid cover zoom in backup");
                    if (coverStore != null) coverStore.importThumbnail(m.coverImage, nullStr(o, "coverThumbnail"));
                    m.releaseStatus = o.optString("releaseStatus", "ongoing");
                    m.createdAt = o.optLong("createdAt", 0); m.updatedAt = o.optLong("updatedAt", 0);
                    m.isFavorite = o.optBoolean("isFavorite", false);
                    db.mediaDao().insert(m);
                    if (o.has("progress") && !o.isNull("progress")) {
                        org.json.JSONObject po = o.getJSONObject("progress");
                        UserProgressEntity p = new UserProgressEntity();
                        p.mediaId = m.id; p.currentProgress = po.optDouble("currentProgress", 0);
                        validateProgress(p.currentProgress);
                        p.rating = po.has("rating") && !po.isNull("rating") ? po.getInt("rating") : null;
                        p.trackingStatus = po.optString("trackingStatus", "plan_to_read");
                        p.notes = nullStr(po, "notes"); p.lastUpdatedAt = po.optLong("lastUpdatedAt", 0);
                        db.progressDao().save(p);
                    }
                    if (o.has("genreIds")) {
                        org.json.JSONArray gIds = o.getJSONArray("genreIds");
                        for (int j = 0; j < gIds.length(); j++)
                            db.genreDao().addToMedia(new MediaGenreCrossRef(m.id, gIds.getLong(j)));
                    }
                    if (o.has("storyMemory") && !o.isNull("storyMemory")) {
                        org.json.JSONObject so = o.getJSONObject("storyMemory");
                        StoryMemoryEntity st = new StoryMemoryEntity(); st.mediaId = m.id;
                        st.mainCharacterName = nullStr(so, "mainCharacterName");
                        st.storySummary = nullStr(so, "storySummary");
                        st.lastStoryPoint = nullStr(so, "lastStoryPoint");
                        st.importantNotes = nullStr(so, "importantNotes");
                        db.storyDao().save(st);
                    }
                    if (o.has("characters")) {
                        org.json.JSONArray chars = o.getJSONArray("characters");
                        for (int j = 0; j < chars.length(); j++) {
                            org.json.JSONObject co = chars.getJSONObject(j);
                            CharacterEntity c = new CharacterEntity();
                            c.id = co.optLong("id", 0); c.mediaId = m.id;
                            c.name = co.getString("name"); c.role = nullStr(co, "role");
                            c.description = nullStr(co, "description");
                            c.image = nullStr(co, "image");
                            if (coverStore != null) coverStore.importThumbnail(c.image, nullStr(co, "imageThumbnail"));
                            db.storyDao().addCharacter(c);
                        }
                    }
                    org.json.JSONArray history = o.optJSONArray("progressHistory");
                    ProgressHistoryEntity previous = null;
                    if (history != null) for (int j = 0; j < history.length(); j++) {
                        org.json.JSONObject entry = history.getJSONObject(j);
                        ProgressHistoryEntity change = new ProgressHistoryEntity();
                        change.id = entry.getLong("id"); change.mediaId = m.id; change.token = entry.getString("token");
                        change.fromProgress = entry.getDouble("from"); change.toProgress = entry.getDouble("to");
                        validateProgress(change.fromProgress); validateProgress(change.toProgress);
                        change.recordedAt = entry.getLong("recordedAt");
                        change.beforeUpdatedAt = entry.getLong("beforeUpdatedAt"); change.afterUpdatedAt = entry.getLong("afterUpdatedAt");
                        change.kind = entry.getString("kind"); change.unit = entry.getString("unit"); change.undoOf = nullStr(entry, "undoOf");
                        if (change.id <= 0 || change.token.isEmpty() || change.fromProgress == change.toProgress
                                || change.recordedAt < 0 || change.beforeUpdatedAt < 0 || change.afterUpdatedAt < 0
                                || !(change.unit.equals("Chapter") || change.unit.equals("Episode"))
                                || !(change.kind.equals("reading") || change.kind.equals("correction") || change.kind.equals("undo"))
                                || change.kind.equals("reading") && change.toProgress < change.fromProgress
                                || change.kind.equals("undo") && (previous == null || previous.kind.equals("undo") || !previous.token.equals(change.undoOf)
                                    || change.toProgress != previous.fromProgress || change.afterUpdatedAt != previous.beforeUpdatedAt)
                                || !change.kind.equals("undo") && change.undoOf != null
                                || previous != null && (change.id <= previous.id || change.fromProgress != previous.toProgress
                                    || change.beforeUpdatedAt != previous.afterUpdatedAt))
                            throw new IllegalArgumentException("Invalid progress history in backup");
                        db.progressHistoryDao().insert(change);
                        previous = change;
                    }
                    if (previous != null) {
                        UserProgressEntity progress = db.progressDao().get(m.id);
                        if (progress == null || progress.currentProgress != previous.toProgress || progress.lastUpdatedAt != previous.afterUpdatedAt)
                            throw new IllegalArgumentException("Progress does not match backup history");
                    }
                }
            } catch (org.json.JSONException e) {
                throw new IllegalArgumentException("Invalid backup data: " + e.getMessage());
            }
        });
        refresh();
    }

    private static String nullStr(org.json.JSONObject o, String k) {
        return o.has(k) && !o.isNull(k) ? o.optString(k) : null;
    }
}
