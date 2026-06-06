package com.example.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Track::class], version = 1, exportSchema = false)
abstract class SyncDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao

    companion object {
        @Volatile
        private var INSTANCE: SyncDatabase? = null

        fun getDatabase(context: Context): SyncDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SyncDatabase::class.java,
                    "syncroom_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
