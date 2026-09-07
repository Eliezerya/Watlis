package com.watlis.app.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "media")
public class MediaEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    @NonNull public String title = "";
    @NonNull public String type = "manga";
    public String coverImage;
    @androidx.room.ColumnInfo(defaultValue = "0.5")
    public float coverPositionX = 0.5f;
    @androidx.room.ColumnInfo(defaultValue = "0.5")
    public float coverPositionY = 0.5f;
    @NonNull public String releaseStatus = "ongoing";
    public long createdAt;
    public long updatedAt;
    public boolean isFavorite;
}
