package com.watlis.app.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "genres", indices = @androidx.room.Index(value = "name", unique = true))
public class GenreEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    @androidx.room.ColumnInfo(collate = androidx.room.ColumnInfo.NOCASE)
    @NonNull public String name = "";
    @NonNull public String color = "#B9F34A";
}
