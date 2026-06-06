package com.example.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.connectivity.BluetoothService
import com.example.connectivity.ConnectionState
import com.example.connectivity.RoomMember
import com.example.connectivity.SyncMessage
import com.example.connectivity.SyncRoomInfo
import com.example.connectivity.UserRole
import com.example.database.SyncDatabase
import com.example.database.SyncRepository
import com.example.database.Track
import com.example.playback.AudioController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "SyncRoom_ViewModel"

    private val database = SyncDatabase.getDatabase(application)
    private val repository = SyncRepository(database.trackDao())

    val bluetoothService = BluetoothService(application)
    val audioController = AudioController(application)

    // Expose database tracks
    val tracks: StateFlow<List<Track>> = repository.allTracks
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val connectionState: StateFlow<ConnectionState> = bluetoothService.connectionState
    val userRole: StateFlow<UserRole> = bluetoothService.role
    val connectedMembers: StateFlow<List<RoomMember>> = bluetoothService.connectedMembers
    val discoveredRooms: StateFlow<List<SyncRoomInfo>> = bluetoothService.discoveredRooms
    val activeRoom: StateFlow<SyncRoomInfo?> = bluetoothService.activeRoom
    val isDemoMode: StateFlow<Boolean> = bluetoothService.isDemoMode

    // Player forwarding
    val isPlaying: StateFlow<Boolean> = audioController.isPlaying
    val currentPosition: StateFlow<Long> = audioController.currentPosition
    val duration: StateFlow<Long> = audioController.duration
    val currentTrackIndex: StateFlow<Int> = audioController.currentTrackIndex
    val repeatMode: StateFlow<Boolean> = audioController.repeatMode
    val shuffleMode: StateFlow<Boolean> = audioController.shuffleMode
    val volume: StateFlow<Float> = audioController.volume
    val activeSyncedDriftMs: StateFlow<Long> = audioController.activeSyncedDriftMs
    val latencyCorrectionEvents: StateFlow<String> = audioController.latencyCorrectionEvents

    private var hostPublishJob: Job? = null
    private var syncMessageCollectionJob: Job? = null

    init {
        prepopulateDefaultTracksIfEmpty()
        observeDatabaseTracks()
        observeSyncMessages()
    }

    private fun prepopulateDefaultTracksIfEmpty() {
        viewModelScope.launch {
            repository.allTracks.collectLatest { list ->
                if (list.isEmpty()) {
                    val defaults = listOf(
                        Track(title = "Neon Dusk City", artist = "Vector Cyber", duration = 184000, path = "asset:///neon_dusk.mp3"),
                        Track(title = "Starlight Cruise", artist = "Retro Driver", duration = 210000, path = "asset:///starlight.mp3"),
                        Track(title = "8-Bit Arcade Jump", artist = "Pixel Pulse", duration = 152000, path = "asset:///pixel_pulse.mp3"),
                        Track(title = "Sub-Zero Horizon", artist = "Synth Shaman", duration = 239000, path = "asset:///horizon.mp3")
                    )
                    repository.insertTracks(defaults)
                }
            }
        }
    }

    private fun observeDatabaseTracks() {
        viewModelScope.launch {
            tracks.collectLatest { list ->
                if (list.isNotEmpty()) {
                    audioController.setPlaylist(list)
                }
            }
        }
    }

    private fun observeSyncMessages() {
        syncMessageCollectionJob = viewModelScope.launch {
            bluetoothService.incomingMessages.collect { msg ->
                val roleVal = userRole.value
                when (msg.type) {
                    SyncMessage.TYPE_PLAY_STATE -> {
                        // Receives dynamic play status and performs high precision delay correction
                        if (roleVal == UserRole.MEMBER) {
                            val parts = msg.payload.split("|")
                            if (parts.size >= 3) {
                                val isPlayingVal = parts[0].toBoolean()
                                val trackIndex = parts[1].toInt()
                                val positionMs = parts[2].toLong()

                                // Capture latency and NTP shift
                                val transmissionLatency = (System.currentTimeMillis() - msg.timestamp).coerceAtLeast(0)
                                audioController.alignPlayback(
                                    peerIsPlaying = isPlayingVal,
                                    trackIndex = trackIndex,
                                    hostPositionMs = positionMs,
                                    transmissionLatencyMs = transmissionLatency
                                )
                            }
                        }
                    }
                    SyncMessage.TYPE_VOLUME -> {
                        if (roleVal == UserRole.MEMBER) {
                            val volVal = msg.payload.toFloatOrNull() ?: 1.0f
                            audioController.setVolume(volVal)
                        }
                    }
                    SyncMessage.TYPE_SEEK -> {
                        if (roleVal == UserRole.MEMBER) {
                            val seekPos = msg.payload.toLongOrNull() ?: 0L
                            audioController.seekTo(seekPos)
                        }
                    }
                }
            }
        }
    }

    // Host publishing loop runs every 500ms to broadcast the playback cursor precisely
    private fun startHostPublishing() {
        hostPublishJob?.cancel()
        hostPublishJob = viewModelScope.launch {
            while (true) {
                if (userRole.value == UserRole.HOST) {
                    val playState = isPlaying.value
                    val trackIdx = currentTrackIndex.value
                    val pos = currentPosition.value
                    
                    bluetoothService.broadcastMessage(SyncMessage(
                        type = SyncMessage.TYPE_PLAY_STATE,
                        payload = "$playState|$trackIdx|$pos"
                    ))
                }
                delay(600)
            }
        }
    }

    private fun stopHostPublishing() {
        hostPublishJob?.cancel()
        hostPublishJob = null
    }

    // --- Action Triggers ---
    fun startHosting(roomName: String, password: String = "") {
        bluetoothService.startHostRoom(roomName, password)
        startHostPublishing()
    }

    fun joinSelectedRoom(room: SyncRoomInfo, password: String = "") {
        bluetoothService.joinRoom(room, password)
    }

    fun exitRoom() {
        bluetoothService.disconnect()
        stopHostPublishing()
        audioController.stop()
    }

    fun togglePlay() {
        val currentIsPlaying = isPlaying.value
        if (currentIsPlaying) {
            audioController.pause()
        } else {
            audioController.play()
        }
        
        // Share immediate state update
        if (userRole.value == UserRole.HOST) {
            broadcastStateUpdate()
        }
    }

    fun triggerNext() {
        audioController.playNext()
        if (userRole.value == UserRole.HOST) {
            broadcastStateUpdate()
        }
    }

    fun triggerPrevious() {
        audioController.playPrevious()
        if (userRole.value == UserRole.HOST) {
            broadcastStateUpdate()
        }
    }

    fun triggerSeek(positionMs: Long) {
        audioController.seekTo(positionMs)
        if (userRole.value == UserRole.HOST) {
            bluetoothService.broadcastMessage(SyncMessage(
                type = SyncMessage.TYPE_SEEK,
                payload = "$positionMs"
            ))
        }
    }

    fun triggerVolumeChange(volValue: Float) {
        audioController.setVolume(volValue)
        if (userRole.value == UserRole.HOST) {
            bluetoothService.broadcastMessage(SyncMessage(
                type = SyncMessage.TYPE_VOLUME,
                payload = "$volValue"
            ))
        }
    }

    fun toggleRepeat() {
        audioController.toggleRepeat()
    }

    fun toggleShuffle() {
        audioController.toggleShuffle()
    }

    fun importCustomTrack(title: String, artist: String, pathUri: String, durationMs: Long) {
        viewModelScope.launch {
            val newTrack = Track(
                title = title,
                artist = artist,
                duration = durationMs,
                path = pathUri,
                isCustom = true
            )
            repository.insertTrack(newTrack)
            Log.d(TAG, "Imported custom track: $title")
        }
    }

    fun deleteTrack(track: Track) {
        viewModelScope.launch {
            repository.deleteTrack(track)
        }
    }

    private fun broadcastStateUpdate() {
        val playState = isPlaying.value
        val trackIdx = currentTrackIndex.value
        val pos = currentPosition.value
        bluetoothService.broadcastMessage(SyncMessage(
            type = SyncMessage.TYPE_PLAY_STATE,
            payload = "$playState|$trackIdx|$pos"
        ))
    }

    override fun onCleared() {
        super.onCleared()
        stopHostPublishing()
        syncMessageCollectionJob?.cancel()
        audioController.release()
        bluetoothService.disconnect()
    }
}
