package com.example.connectivity

import androidx.compose.runtime.Immutable

enum class UserRole {
    HOST,
    MEMBER,
    NONE
}

enum class ConnectionState {
    IDLE,
    SCANNING,
    ADVERTISING,
    CONNECTING,
    CONNECTED,
    DISCONNECTED
}

@Immutable
data class RoomMember(
    val id: String,
    val name: String,
    val isApproved: Boolean = false,
    val latencyMs: Long = 0L,
    val isHost: Boolean = false
)

@Immutable
data class SyncRoomInfo(
    val id: String = "",
    val name: String = "",
    val hostName: String = "",
    val requiresPassword: Boolean = false,
    val password: String = "",
    val pingMs: Long = 0L,
    val clockOffsetMs: Long = 0L,
    val memberCount: Int = 0
)

@Immutable
data class SyncMessage(
    val type: String,
    val timestamp: Long = System.currentTimeMillis(),
    val payload: String = ""
) {
    companion object {
        const val TYPE_JOIN_REQUEST = "JOIN_REQ"
        const val TYPE_JOIN_RESPONSE = "JOIN_RES"
        const val TYPE_MESSAGES = "MSG"
        const val TYPE_PLAY_STATE = "PLAY"
        const val TYPE_SEEK = "SEEK"
        const val TYPE_PING = "PING"
        const val TYPE_PONG = "PONG"
        const val TYPE_VOLUME = "VOL"
        const val TYPE_TRACK_CHANGE = "TRACK"
        const val TYPE_PLAYLIST = "PLAYLIST"
        const val TYPE_MEMBER_UPDATE = "MEMBERS"
    }
}
