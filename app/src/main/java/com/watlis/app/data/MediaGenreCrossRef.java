package com.watlis.app.data;

import androidx.room.Entity;
import androidx.room.ForeignKey;

@Entity(tableName = "media_genres", primaryKeys = {"mediaId", "genreId"}, indices = @androidx.room.Index("genreId"), foreignKeys = {
        @ForeignKey(entity = MediaEntity.class, parentColumns = "id", childColumns = "mediaId", onDelete = ForeignKey.CASCADE),
        @ForeignKey(entity = GenreEntity.class, parentColumns = "id", childColumns = "genreId", onDelete = ForeignKey.CASCADE)
})
public class MediaGenreCrossRef {
    public long mediaId;
    public long genreId;
    public MediaGenreCrossRef(long mediaId, long genreId) { this.mediaId = mediaId; this.genreId = genreId; }
}
