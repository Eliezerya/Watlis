package com.watlis.app.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** A committed change, not an inferred reading session. IDs define order even when timestamps match. */
@Entity(tableName = "progress_history", indices = {
        @Index(value = {"mediaId", "id"}), @Index(value = "token", unique = true)
}, foreignKeys = @ForeignKey(entity = MediaEntity.class, parentColumns = "id", childColumns = "mediaId", onDelete = ForeignKey.CASCADE))
public class ProgressHistoryEntity {
    @PrimaryKey(autoGenerate = true) public long id;
    public long mediaId;
    @NonNull public String token = java.util.UUID.randomUUID().toString();
    public double fromProgress;
    public double toProgress;
    public long recordedAt;
    public long beforeUpdatedAt;
    public long afterUpdatedAt;
    @NonNull public String kind = "correction";
    @NonNull public String unit = "Chapter";
    public String undoOf;
}
