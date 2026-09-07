package com.watlis.app.data;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.PrimaryKey;

@Entity(tableName = "user_progress", foreignKeys = @ForeignKey(entity = MediaEntity.class, parentColumns = "id", childColumns = "mediaId", onDelete = ForeignKey.CASCADE))
public class UserProgressEntity {
    @PrimaryKey public long mediaId;
    public double currentProgress;
    public Integer rating;
    public String trackingStatus = "plan_to_read";
    public String notes;
    public long lastUpdatedAt;
}
