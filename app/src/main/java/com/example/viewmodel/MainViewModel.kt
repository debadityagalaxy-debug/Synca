package com.example.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.connectivity.WifiService
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.squareup.moshi.Types
import android.util.Base64

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "SyncRoom_ViewModel"

    private val database = SyncDatabase.getDatabase(application)
    private val repository = SyncRepository(database.trackDao())

    val wifiService = WifiService(application)
    val audioController = AudioController(application)

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val trackListAdapter = moshi.adapter<List<Track>>(Types.newParameterizedType(List::class.java, Track::class.java))

    private val _syncedTracks = MutableStateFlow<List<Track>>(emptyList())

    val connectionState: StateFlow<ConnectionState> = wifiService.connectionState
    val userRole: StateFlow<UserRole> = wifiService.role
    val connectedMembers: StateFlow<List<RoomMember>> = wifiService.connectedMembers
    val discoveredRooms: StateFlow<List<SyncRoomInfo>> = wifiService.discoveredRooms
    val activeRoom: StateFlow<SyncRoomInfo?> = wifiService.activeRoom

    // Expose database tracks
    val tracks: StateFlow<List<Track>> = combine(
        repository.allTracks,
        _syncedTracks,
        userRole
    ) { localTracks, syncedTracks, role ->
        if (role == UserRole.MEMBER) {
            syncedTracks
        } else {
            localTracks
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

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

    private val _manualLatencyOffset = MutableStateFlow(0L)
    val manualLatencyOffset: StateFlow<Long> = _manualLatencyOffset.asStateFlow()

    fun updateManualLatencyOffset(offset: Long) {
        _manualLatencyOffset.value = offset
    }

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
                        Track(title = "SoundHelix Song 1", artist = "T. Schürger", duration = 372000, path = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"),
                        Track(title = "SoundHelix Song 2", artist = "T. Schürger", duration = 425000, path = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3"),
                        Track(title = "SoundHelix Song 3", artist = "T. Schürger", duration = 345000, path = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-3.mp3"),
                        Track(title = "SoundHelix Song 4", artist = "T. Schürger", duration = 302000, path = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-4.mp3")
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

        viewModelScope.launch {
            combine(tracks, userRole, connectedMembers) { t, r, mem -> Triple(t, r, mem.size) }
                .collectLatest { (list, role, _) ->
                    if (role == UserRole.HOST) {
                        try {
                            val json = trackListAdapter.toJson(list)
                            val b64 = Base64.encodeToString(json.toByteArray(), Base64.NO_WRAP)
                            wifiService.broadcastMessage(SyncMessage(SyncMessage.TYPE_PLAYLIST, payload = b64))
                        } catch (e: Exception) {
                            Log.e("MainViewModel", "Failed to encode playlist", e)
                        }
                    }
                }
        }
    }

    private fun observeSyncMessages() {
        syncMessageCollectionJob = viewModelScope.launch {
            wifiService.incomingMessages.collect { msg ->
                val roleVal = userRole.value
                when (msg.type) {
                    SyncMessage.TYPE_PLAYLIST -> {
                        if (roleVal == UserRole.MEMBER) {
                            try {
                                val json = String(Base64.decode(msg.payload, Base64.NO_WRAP))
                                val list = trackListAdapter.fromJson(json) ?: emptyList()
                                _syncedTracks.value = list
                            } catch (e: Exception) {
                                Log.e("MainViewModel", "Failed to parse playlist", e)
                            }
                        }
                    }
                    SyncMessage.TYPE_PLAY_STATE -> {
                        // Receives dynamic play status and performs high precision delay correction
                        if (roleVal == UserRole.MEMBER) {
                            val parts = msg.payload.split("|")
                            if (parts.size >= 3) {
                                val isPlayingVal = parts[0].toBoolean()
                                val trackIndex = parts[1].toInt()
                                val positionMs = parts[2].toLong()

                                // Capture latency using an estimated bluetooth delay since raw system clocks differ
                                val transmissionLatency = 50L + _manualLatencyOffset.value
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

    // Host publishing loop runs periodically to combat clock drift
    private fun startHostPublishing() {
        hostPublishJob?.cancel()
        hostPublishJob = viewModelScope.launch {
            while (true) {
                if (userRole.value == UserRole.HOST) {
                    val playState = isPlaying.value
                    val trackIdx = currentTrackIndex.value
                    val pos = audioController.getExactPosition()
                    
                    wifiService.broadcastMessage(SyncMessage(
                        type = SyncMessage.TYPE_PLAY_STATE,
                        payload = "$playState|$trackIdx|$pos"
                    ))
                }
                delay(1500)
            }
        }
    }

    private fun stopHostPublishing() {
        hostPublishJob?.cancel()
        hostPublishJob = null
    }

    // --- Action Triggers ---
    fun startHosting(roomName: String, password: String = "") {
        wifiService.startHostRoom(roomName, password)
        startHostPublishing()
    }

    fun joinSelectedRoom(room: SyncRoomInfo, password: String = "") {
        wifiService.joinRoom(room, password)
    }

    fun joinRoomById(deviceId: String, password: String = "") {
        val synthezisedRoom = SyncRoomInfo(
            id = deviceId,
            name = "Direct Connection",
            hostName = "Direct Host",
            requiresPassword = password.isNotEmpty(),
            password = password
        )
        wifiService.joinRoom(synthezisedRoom, password)
    }

    fun exitRoom() {
        wifiService.disconnect()
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
            wifiService.broadcastMessage(SyncMessage(
                type = SyncMessage.TYPE_SEEK,
                payload = "$positionMs"
            ))
        }
    }

    fun triggerVolumeChange(volValue: Float) {
        audioController.setVolume(volValue)
        if (userRole.value == UserRole.HOST) {
            wifiService.broadcastMessage(SyncMessage(
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
        val pos = audioController.getExactPosition()
        wifiService.broadcastMessage(SyncMessage(
            type = SyncMessage.TYPE_PLAY_STATE,
            payload = "$playState|$trackIdx|$pos"
        ))
    }

    override fun onCleared() {
        super.onCleared()
        stopHostPublishing()
        syncMessageCollectionJob?.cancel()
        audioController.release()
        wifiService.disconnect()
    }
}
