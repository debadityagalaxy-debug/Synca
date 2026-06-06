package com.example.connectivity

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

@SuppressLint("MissingPermission")
class BluetoothService(private val context: Context) {
    private val TAG = "SyncRoom_BTService"
    private val APP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // Standard Serial Port Profile SPP UUID

    private val hardwareManager = BluetoothManager(context)
    private val bluetoothAdapter: BluetoothAdapter? = hardwareManager.bluetoothAdapter

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

    // Threads
    private var acceptThread: AcceptThread? = null
    private var connectThread: ConnectThread? = null
    private val activeConnections = mutableListOf<ConnectedThread>()

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        // Collect hardware discovered and paired devices and map them to our Room abstraction
        serviceScope.launch {
            hardwareManager.discoveredDevices.collect { devices ->
                val allDevices = (hardwareManager.getPairedDevices() + devices).distinctBy { it.macAddress }
                _discoveredRooms.value = allDevices.map { dev ->
                    SyncRoomInfo(
                        id = dev.macAddress,
                        name = "${dev.name} (${if (dev.bondState == BluetoothDevice.BOND_BONDED) "Paired" else "Discovered"})",
                        hostName = "Device",
                        requiresPassword = false,
                        password = "",
                        memberCount = 1
                    )
                }
            }
        }
        
        serviceScope.launch {
            hardwareManager.connectionState.collect { state ->
                // Sync scanning state
                if (state == ConnectionState.SCANNING) {
                    _connectionState.value = ConnectionState.SCANNING
                } else if (_connectionState.value == ConnectionState.SCANNING) {
                    _connectionState.value = ConnectionState.IDLE
                }
            }
        }
    }

    // Start room hosting
    fun startHostRoom(roomName: String, password: String = "") {
        if (bluetoothAdapter == null) return
        closeAllRealConnections()

        _role.value = UserRole.HOST
        _connectionState.value = ConnectionState.ADVERTISING
        _activeRoom.value = SyncRoomInfo(
            id = bluetoothAdapter.address ?: "bt_host",
            name = roomName,
            hostName = bluetoothAdapter.name ?: "My Host",
            requiresPassword = password.isNotEmpty(),
            password = password
        )

        acceptThread = AcceptThread(roomName)
        acceptThread?.start()
    }

    fun approveMember(memberId: String) {
        val current = _connectedMembers.value.map {
            if (it.id == memberId) it.copy(isApproved = true) else it
        }
        _connectedMembers.value = current

        // Send confirmation signal over protocol
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

    // Join room
    fun joinRoom(room: SyncRoomInfo, providedPassword: String = "") {
        if (bluetoothAdapter == null) return
        closeAllRealConnections()

        hardwareManager.stopDiscovery()

        _role.value = UserRole.MEMBER
        _connectionState.value = ConnectionState.CONNECTING
        _activeRoom.value = room

        val device = bluetoothAdapter.getRemoteDevice(room.id)
        connectThread = ConnectThread(device)
        connectThread?.start()
    }

    // Shutdown structures cleanly
    fun disconnect() {
        closeAllRealConnections()
        _role.value = UserRole.NONE
        _connectionState.value = ConnectionState.IDLE
        _activeRoom.value = null
        _connectedMembers.value = emptyList()
    }

    private fun closeAllRealConnections() {
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

    // Messaging pipeline
    fun broadcastMessage(message: SyncMessage) {
        val dataStr = "${message.type}|${message.timestamp}|${message.payload}\n"
        synchronized(activeConnections) {
            activeConnections.forEach { clientThread ->
                clientThread.write(dataStr.toByteArray())
            }
        }
    }

    fun startDiscovery() {
        hardwareManager.startDiscovery()
    }

    fun stopDiscovery() {
        hardwareManager.stopDiscovery()
    }

    // --- AcceptThread: Server Socket Listener ---
    private inner class AcceptThread(roomName: String) : Thread() {
        private val mmServerSocket: BluetoothServerSocket? by lazy(LazyThreadSafetyMode.NONE) {
            bluetoothAdapter?.listenUsingInsecureRfcommWithServiceRecord(roomName, APP_UUID)
        }

        override fun run() {
            var shouldLoop = true
            while (shouldLoop) {
                val socket: BluetoothSocket? = try {
                    mmServerSocket?.accept()
                } catch (e: IOException) {
                    Log.e(TAG, "Socket Server accept() failed", e)
                    shouldLoop = false
                    null
                }
                socket?.let {
                    manageConnectedSocket(it)
                }
            }
        }

        fun cancel() {
            try {
                mmServerSocket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Socket Server close() failed", e)
            }
        }
    }

    // --- ConnectThread: Client Connection ---
    private inner class ConnectThread(private val device: BluetoothDevice) : Thread() {
        private val mmSocket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            device.createInsecureRfcommSocketToServiceRecord(APP_UUID)
        }

        override fun run() {
            bluetoothAdapter?.cancelDiscovery()

            try {
                mmSocket?.let { socket ->
                    socket.connect()
                    manageConnectedSocket(socket)
                }
            } catch (e: IOException) {
                Log.e(TAG, "Client socket connect() failed", e)
                _connectionState.value = ConnectionState.DISCONNECTED
                try {
                    mmSocket?.close()
                } catch (closeException: IOException) {
                    Log.e(TAG, "Could not close client socket", closeException)
                }
            }
        }

        fun cancel() {
            try {
                mmSocket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Could not close client socket", e)
            }
        }
    }

    private fun manageConnectedSocket(socket: BluetoothSocket) {
        val connectedThread = ConnectedThread(socket)
        synchronized(activeConnections) {
            activeConnections.add(connectedThread)
        }
        connectedThread.start()
        _connectionState.value = ConnectionState.CONNECTED

        // Welcome new user
        val deviceName = socket.remoteDevice.name ?: "Unknown Peer"
        val deviceId = socket.remoteDevice.address ?: UUID.randomUUID().toString()
        
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
            // Member side
            serviceScope.launch {
                _connectedMembers.value = listOf(
                    RoomMember("host_id", _activeRoom.value?.hostName ?: "Host", isApproved = true, isHost = true),
                    RoomMember("my_id", Build.MODEL, isApproved = true, isHost = false, latencyMs = 15L)
                )
            }
        }
    }

    // --- ConnectedThread: Handles Active Comm Channel ---
    private inner class ConnectedThread(private val socket: BluetoothSocket) : Thread() {
        private val mmInStream: InputStream = socket.inputStream
        private val mmOutStream: OutputStream = socket.outputStream
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
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the connect socket", e)
            }
        }
    }

    private fun handleDisconnect(socket: BluetoothSocket) {
        synchronized(activeConnections) {
            activeConnections.removeAll { it == Thread.currentThread() }
        }
        val deviceId = socket.remoteDevice?.address ?: ""
        _connectedMembers.value = _connectedMembers.value.filter { it.id != deviceId }
        updateRoomMemberCount()

        if (_role.value == UserRole.MEMBER && activeConnections.isEmpty()) {
            _connectionState.value = ConnectionState.DISCONNECTED
            // Trigger automatic retry/reconnection
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
