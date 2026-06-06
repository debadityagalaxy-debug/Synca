package com.example.connectivity

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID

class WifiService(private val context: Context) {
    private val TAG = "SyncRoom_WifiService"
    private val TCP_PORT = 8888
    private val UDP_PORT = 8889

    // State flows
    private val _connectionState = MutableStateFlow(ConnectionState.IDLE)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _role = MutableStateFlow(UserRole.NONE)
    val role: StateFlow<UserRole> = _role.asStateFlow()

    private val _connectedMembers = MutableStateFlow<List<RoomMember>>(emptyList())
    val connectedMembers: StateFlow<List<RoomMember>> = _connectedMembers.asStateFlow()

    private val _discoveredRooms = MutableStateFlow<List<SyncRoomInfo>>(emptyList())
    val discoveredRooms: StateFlow<List<SyncRoomInfo>> = _discoveredRooms.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<SyncMessage>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<SyncMessage> = _incomingMessages.asSharedFlow()

    private val _activeRoom = MutableStateFlow<SyncRoomInfo?>(null)
    val activeRoom: StateFlow<SyncRoomInfo?> = _activeRoom.asStateFlow()

    // Threads and sockets
    private var tcpServerSocket: ServerSocket? = null
    private var acceptThread: AcceptThread? = null
    private var connectThread: ConnectThread? = null
    private val activeConnections = mutableListOf<ConnectedThread>()

    private var udpBroadcastJob: Job? = null
    private var udpListenJob: Job? = null

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Start room hosting
    fun startHostRoom(roomName: String, password: String = "") {
        closeAllConnections()

        _role.value = UserRole.HOST
        _connectionState.value = ConnectionState.ADVERTISING
        _activeRoom.value = SyncRoomInfo(
            id = "host_ip",
            name = roomName,
            hostName = Build.MODEL,
            requiresPassword = password.isNotEmpty(),
            password = password
        )

        acceptThread = AcceptThread()
        acceptThread?.start()

        startUdpBroadcast(roomName)
    }

    private fun startUdpBroadcast(roomName: String) {
        udpBroadcastJob?.cancel()
        udpBroadcastJob = serviceScope.launch {
            try {
                val udpSocket = DatagramSocket()
                udpSocket.broadcast = true
                val broadcastAddress = InetAddress.getByName("255.255.255.255")
                val messageStr = "SYNCROOM_WIFI|$roomName"
                val buffer = messageStr.toByteArray()

                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size, broadcastAddress, UDP_PORT)
                        udpSocket.send(packet)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error broadcasting UDP", e)
                    }
                    delay(2000)
                }
                udpSocket.close()
            } catch (e: Exception) {
                 Log.e(TAG, "Failed to start UDP Broadcast", e)
            }
        }
    }

    fun startDiscovery() {
        if (_connectionState.value == ConnectionState.SCANNING) return
        _connectionState.value = ConnectionState.SCANNING
        _discoveredRooms.value = emptyList()

        udpListenJob?.cancel()
        udpListenJob = serviceScope.launch {
            try {
                val udpSocket = DatagramSocket(UDP_PORT)
                udpSocket.soTimeout = 3000
                val buffer = ByteArray(1024)

                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        udpSocket.receive(packet)
                        val data = String(packet.data, 0, packet.length)
                        if (data.startsWith("SYNCROOM_WIFI|")) {
                            val roomName = data.substringAfter("|")
                            val senderIp = packet.address.hostAddress ?: continue
                            
                            val rooms = _discoveredRooms.value.toMutableList()
                            if (rooms.none { it.id == senderIp }) {
                                rooms.add(
                                    SyncRoomInfo(
                                        id = senderIp,
                                        name = roomName,
                                        hostName = "Wi-Fi Host",
                                        requiresPassword = false,
                                        password = "",
                                        memberCount = 1
                                    )
                                )
                                _discoveredRooms.value = rooms
                            }
                        }
                    } catch (e: SocketTimeoutException) {
                        // ignore timeout, just loop
                    } catch (e: Exception) {
                        Log.e(TAG, "Error receiving UDP", e)
                    }
                }
                udpSocket.close()
            } catch (e: Exception) {
                 Log.e(TAG, "Failed to start UDP Listener", e)
            }
        }
    }

    fun stopDiscovery() {
        udpListenJob?.cancel()
        if (_connectionState.value == ConnectionState.SCANNING) {
            _connectionState.value = ConnectionState.IDLE
        }
    }

    fun approveMember(memberId: String) {
        val current = _connectedMembers.value.map {
            if (it.id == memberId) it.copy(isApproved = true) else it
        }
        _connectedMembers.value = current
        broadcastMessage(SyncMessage(SyncMessage.TYPE_JOIN_RESPONSE, payload = "APPROVED|$memberId"))
        updateRoomMemberCount()
    }

    fun rejectMember(memberId: String) {
        _connectedMembers.value = _connectedMembers.value.filter { it.id != memberId }
        broadcastMessage(SyncMessage(SyncMessage.TYPE_JOIN_RESPONSE, payload = "REJECTED|$memberId"))
        updateRoomMemberCount()
    }

    private fun updateRoomMemberCount() {
        val approvedCount = _connectedMembers.value.count { it.isApproved }
        _activeRoom.value = _activeRoom.value?.copy(memberCount = approvedCount)
    }

    fun joinRoom(room: SyncRoomInfo, providedPassword: String = "") {
        closeAllConnections()
        stopDiscovery()

        _role.value = UserRole.MEMBER
        _connectionState.value = ConnectionState.CONNECTING
        _activeRoom.value = room

        connectThread = ConnectThread(room.id)
        connectThread?.start()
    }

    fun disconnect() {
        closeAllConnections()
        _role.value = UserRole.NONE
        _connectionState.value = ConnectionState.IDLE
        _activeRoom.value = null
        _connectedMembers.value = emptyList()
    }

    private fun closeAllConnections() {
        udpBroadcastJob?.cancel()
        udpListenJob?.cancel()
        try {
            tcpServerSocket?.close()
        } catch (e: Exception) {}
        try {
            acceptThread?.cancel()
            acceptThread = null
            connectThread?.cancel()
            connectThread = null
            synchronized(activeConnections) {
                activeConnections.forEach { it.cancel() }
                activeConnections.clear()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error closing connections", e)
        }
    }

    fun broadcastMessage(message: SyncMessage) {
        val dataStr = "${message.type}|${message.timestamp}|${message.payload}\n"
        synchronized(activeConnections) {
            activeConnections.forEach { clientThread ->
                clientThread.write(dataStr.toByteArray())
            }
        }
    }

    private inner class AcceptThread : Thread() {
        override fun run() {
            try {
                tcpServerSocket = ServerSocket(TCP_PORT)
                tcpServerSocket?.reuseAddress = true
                var shouldLoop = true
                while (shouldLoop) {
                    val socket: Socket? = try {
                        tcpServerSocket?.accept()
                    } catch (e: IOException) {
                        Log.e(TAG, "Socket Server accept() failed", e)
                        shouldLoop = false
                        null
                    }
                    socket?.let {
                        manageConnectedSocket(it)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not start server socket", e)
            }
        }

        fun cancel() {
            try {
                tcpServerSocket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Socket Server close() failed", e)
            }
        }
    }

    private inner class ConnectThread(private val hostIp: String) : Thread() {
        private var mmSocket: Socket? = null

        override fun run() {
            try {
                mmSocket = Socket(hostIp, TCP_PORT)
                mmSocket?.let { socket ->
                    manageConnectedSocket(socket)
                }
            } catch (e: IOException) {
                Log.e(TAG, "Client socket connect() failed", e)
                _connectionState.value = ConnectionState.DISCONNECTED
                try {
                    mmSocket?.close()
                } catch (closeException: IOException) { }
            }
        }

        fun cancel() {
            try {
                mmSocket?.close()
            } catch (e: IOException) { }
        }
    }

    private fun manageConnectedSocket(socket: Socket) {
        val connectedThread = ConnectedThread(socket)
        synchronized(activeConnections) {
            activeConnections.add(connectedThread)
        }
        connectedThread.start()
        _connectionState.value = ConnectionState.CONNECTED

        val deviceName = "Peer"
        val deviceId = socket.inetAddress?.hostAddress ?: UUID.randomUUID().toString()
        
        if (_role.value == UserRole.HOST) {
            serviceScope.launch {
                val current = _connectedMembers.value.toMutableList()
                val isDuplicate = current.any { it.id == deviceId }
                if (!isDuplicate) {
                    val newMember = RoomMember(deviceId, deviceName, isApproved = true, latencyMs = 24L)
                    current.add(newMember)
                    _connectedMembers.value = current
                    _incomingMessages.emit(SyncMessage(SyncMessage.TYPE_JOIN_REQUEST, payload = "$deviceId|$deviceName"))
                }
            }
        } else {
            serviceScope.launch {
                _connectedMembers.value = listOf(
                    RoomMember("host_id", _activeRoom.value?.hostName ?: "Host", isApproved = true, isHost = true),
                    RoomMember("my_id", Build.MODEL, isApproved = true, isHost = false, latencyMs = 15L)
                )
            }
        }
    }

    private inner class ConnectedThread(private val socket: Socket) : Thread() {
        private val mmInStream: InputStream = socket.getInputStream()
        private val mmOutStream: OutputStream = socket.getOutputStream()
        private val mmBuffer = ByteArray(1024)

        override fun run() {
            while (true) {
                val numBytes = try {
                    mmInStream.read(mmBuffer)
                } catch (e: IOException) {
                    Log.d(TAG, "Input stream was disconnected", e)
                    handleDisconnect(socket)
                    break
                }

                if (numBytes == -1) {
                    handleDisconnect(socket)
                    break
                }

                val dataStr = String(mmBuffer, 0, numBytes)
                parseReceivedPayload(dataStr)
            }
        }

        fun write(bytes: ByteArray) {
            try {
                mmOutStream.write(bytes)
            } catch (e: IOException) {
                Log.e(TAG, "Error occurred when sending data", e)
            }
        }

        fun cancel() {
            try {
                socket.close()
            } catch (e: IOException) {}
        }
    }

    private fun handleDisconnect(socket: Socket) {
        synchronized(activeConnections) {
            activeConnections.removeAll { it == Thread.currentThread() }
        }
        val deviceId = socket.inetAddress?.hostAddress ?: ""
        _connectedMembers.value = _connectedMembers.value.filter { it.id != deviceId }
        updateRoomMemberCount()

        if (_role.value == UserRole.MEMBER && activeConnections.isEmpty()) {
            _connectionState.value = ConnectionState.DISCONNECTED
            serviceScope.launch {
                delay(3000)
                if (_connectionState.value == ConnectionState.DISCONNECTED && _activeRoom.value != null) {
                    Log.d(TAG, "Attempting automatic reconnection...")
                    _activeRoom.value?.let { joinRoom(it) }
                }
            }
        }
    }

    private fun parseReceivedPayload(payload: String) {
        val lines = payload.split("\n")
        for (line in lines) {
            if (line.trim().isEmpty()) continue
            val parts = line.split("|", limit = 3)
            if (parts.size >= 2) {
                val type = parts[0]
                val timestamp = parts[1].toLongOrNull() ?: System.currentTimeMillis()
                val content = if (parts.size == 3) parts[2] else ""
                serviceScope.launch {
                    _incomingMessages.emit(SyncMessage(type, timestamp, content))
                }
            }
        }
    }
}

