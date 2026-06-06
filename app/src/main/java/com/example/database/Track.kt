package com.example.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class Track(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val artist: String,
    val duration: Long, // in milliseconds
    val path: String, // Dynamic URI or local asset placeholder
    val isCustom: Boolean = false
)
