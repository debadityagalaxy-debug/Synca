package com.example.database

import kotlinx.coroutines.flow.Flow

class SyncRepository(private val trackDao: TrackDao) {
    val allTracks: Flow<List<Track>> = trackDao.getAllTracks()

    suspend fun insertTrack(track: Track) {
        trackDao.insertTrack(track)
    }

    suspend fun insertTracks(tracks: List<Track>) {
        trackDao.insertTracks(tracks)
    }

    suspend fun deleteTrack(track: Track) {
        trackDao.deleteTrack(track)
    }

    suspend fun clearAll() {
        trackDao.clearAll()
    }
}
