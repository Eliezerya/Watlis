package com.watlis.app.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "media_types", indices = {@Index(value = "name", unique = true)})
public class MediaTypeEntity {
    @PrimaryKey @NonNull public String key = "";
    @NonNull @ColumnInfo(collate = ColumnInfo.NOCASE) public String name = "";
    public boolean usesEpisodes;
}
