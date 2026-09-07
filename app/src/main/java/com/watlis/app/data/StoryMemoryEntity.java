package com.watlis.app.data;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.PrimaryKey;

@Entity(tableName = "story_memory", foreignKeys = @ForeignKey(entity = MediaEntity.class, parentColumns = "id", childColumns = "mediaId", onDelete = ForeignKey.CASCADE))
public class StoryMemoryEntity {
    @PrimaryKey public long mediaId;
    public String mainCharacterName;
    public String storySummary;
    public String lastStoryPoint;
    public String importantNotes;
}
