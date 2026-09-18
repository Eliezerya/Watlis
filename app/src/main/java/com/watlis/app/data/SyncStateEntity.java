package com.watlis.app.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Sync identity is independent of device-local Room row IDs. Tombstones are retained. */
@Entity(tableName = "sync_state")
public class SyncStateEntity {
    @PrimaryKey @NonNull public String key = "";
    public String localKey;
    @NonNull public String hash = "";
    @NonNull public String clock = "{}";
    @NonNull public String label = "";
    public boolean deleted;
}
