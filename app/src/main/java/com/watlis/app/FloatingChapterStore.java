package com.watlis.app;

import android.graphics.Color;
import com.watlis.app.data.*;
import java.util.List;

/** Small indexed reads only; shares the existing atomic progress/history writer. Worker-thread only. */
final class FloatingChapterStore {
    static final class State {
        final long id, createdAt;
        final String title, unit;
        final double progress;
        final int accent;
        State(MediaEntity media, UserProgressEntity progress, MediaTypeEntity type, List<GenreEntity> genres) {
            id = media.id; createdAt = media.createdAt; title = media.title;
            this.progress = progress.currentProgress; unit = type != null && type.usesEpisodes ? "Episode" : "Chapter";
            int color = Color.rgb(199, 237, 154);
            if (!genres.isEmpty()) try { color = Color.parseColor(genres.get(0).color); } catch (IllegalArgumentException ignored) { }
            while (androidx.core.graphics.ColorUtils.calculateContrast(color, Color.rgb(27, 33, 29)) < 4.5)
                color = androidx.core.graphics.ColorUtils.blendARGB(color, Color.WHITE, .15f);
            accent = color;
        }
    }
    private final WatlisDatabase db;
    private final WatlisRepository repo;
    FloatingChapterStore(WatlisDatabase db) { this.db = db; repo = new WatlisRepository(db); }
    State read(long id, Long expectedCreatedAt) {
        return db.runInTransaction(() -> readInside(id, expectedCreatedAt));
    }
    private State readInside(long id, Long expectedCreatedAt) {
        MediaEntity media = db.mediaDao().getById(id); UserProgressEntity progress = db.progressDao().get(id);
        if (media == null || progress == null || expectedCreatedAt != null && media.createdAt != expectedCreatedAt) return null;
        return new State(media, progress, db.mediaTypeDao().get(media.type), db.genreDao().forMedia(id));
    }
    State increment(long id, long createdAt, double delta) {
        if (delta != -1 && delta != 1) throw new IllegalArgumentException("Use the plus or minus control.");
        return db.runInTransaction(() -> {
            if (readInside(id, createdAt) == null) return null;
            repo.incrementProgress(id, delta);
            return readInside(id, createdAt);
        });
    }
}
