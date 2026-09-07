package com.watlis.app.data;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.PrimaryKey;

@Entity(tableName = "characters", indices = @androidx.room.Index("mediaId"), foreignKeys = @ForeignKey(entity = MediaEntity.class, parentColumns = "id", childColumns = "mediaId", onDelete = ForeignKey.CASCADE))
public class CharacterEntity {
    @PrimaryKey(autoGenerate = true) public long id;
    public long mediaId;
    public String name = "";
    public String role;
    public String description;
}
