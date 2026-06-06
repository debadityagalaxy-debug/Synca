package com.example.connectivity

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager as SystemBluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Model representing a discovered physical Bluetooth device in the vicinity.
 */
data class DiscoveredDevice(
    val macAddress: String,
    val name: String,
    val bondState: Int,
    val rssi: Short = 0
)

/**
 * Robust BluetoothManager utility to manage low-level adapter utilities, physical device scanning,
 * and mapping connection states within the SyncRoom app.
 */
@SuppressLint("MissingPermission")
class BluetoothManager(private val context: Context) {
    private val TAG = "SyncRoom_BTManager"

    private val systemBluetoothManager: SystemBluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? SystemBluetoothManager

    val bluetoothAdapter: BluetoothAdapter? = systemBluetoothManager?.adapter

    // State flows reflecting current physical hardware situations
    private val _isBluetoothSupported = MutableStateFlow(bluetoothAdapter != null)
    val isBluetoothSupported: StateFlow<Boolean> = _isBluetoothSupported.asStateFlow()

    private val _isBluetoothEnabled = MutableStateFlow(bluetoothAdapter?.isEnabled == true)
    val isBluetoothEnabled: StateFlow<Boolean> = _isBluetoothEnabled.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.IDLE)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Register state indicator
    private var isReceiverRegistered = false

    /**
     * BroadcastReceiver to catch asynchronous Bluetooth subsystem updates.
     */
    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, 0)
                    device?.let { dev ->
                        val devName = dev.name ?: "Unlabeled Device"
                        val discovered = DiscoveredDevice(
                            macAddress = dev.address,
                            name = devName,
                            bondState = dev.bondState,
                            rssi = rssi
                        )
                        addDiscoveredDevice(discovered)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    _isScanning.value = true
                    _connectionState.value = ConnectionState.SCANNING
                    Log.d(TAG, "Hardware Bluetooth discovery started...")
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _isScanning.value = false
                    if (_connectionState.value == ConnectionState.SCANNING) {
                        _connectionState.value = ConnectionState.IDLE
                    }
                    Log.d(TAG, "Hardware Bluetooth discovery finished.")
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)
                    _isBluetoothEnabled.value = (state == BluetoothAdapter.STATE_ON)
                }
            }
        }
    }

    /**
     * Retrieves the set of runtime permission strings required under current Android API level.
     */
    fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        return permissions
    }

    /**
     * Verifies if all necessary permissions are granted.
     */
    fun hasRequiredPermissions(): Boolean {
        return getRequiredPermissions().all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Retrieves devices that have been authenticated/paired previously in safe system lists.
     */
    fun getPairedDevices(): List<DiscoveredDevice> {
        if (bluetoothAdapter == null || !hasRequiredPermissions()) {
            return emptyList()
        }
        return try {
            bluetoothAdapter.bondedDevices.map { device ->
                DiscoveredDevice(
                    macAddress = device.address,
                    name = device.name ?: "Unknown Paired Device",
                    bondState = device.bondState
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching bonded devices", e)
            emptyList()
        }
    }

    /**
     * Registers receivers safely for observing scanning results.
     */
    fun registerDiscoveryReceiver() {
        if (isReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            context.registerReceiver(discoveryReceiver, filter)
            isReceiverRegistered = true
            Log.d(TAG, "Registered Bluetooth discovery broadcast receiver.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register discovery receiver", e)
        }
    }

    /**
     * Releases active broadcast receivers to prevent memory leaks.
     */
    fun unregisterDiscoveryReceiver() {
        if (!isReceiverRegistered) return
        try {
            context.unregisterReceiver(discoveryReceiver)
            isReceiverRegistered = false
            Log.d(TAG, "Unregistered Bluetooth discovery broadcast receiver.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister discovery receiver", e)
        }
    }

    /**
     * Starts active physical device discovery scanning.
     */
    fun startDiscovery() {
        registerDiscoveryReceiver()
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Discovery failed: hardware client is not supported")
            return
        }
        if (!hasRequiredPermissions()) {
            Log.e(TAG, "Discovery failed: missing required permissions")
            return
        }

        try {
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
            _discoveredDevices.value = getPairedDevices() // Show paired immediately
            val success = bluetoothAdapter.startDiscovery()
            _isScanning.value = success
            if (success) {
                _connectionState.value = ConnectionState.SCANNING
            }
            Log.d(TAG, "Initiated startDiscovery scan: status = $success")
        } catch (e: Exception) {
            Log.e(TAG, "Error execution for startDiscovery", e)
        }
    }

    /**
     * Stop active physical device discovery scanning.
     */
    fun stopDiscovery() {
        if (bluetoothAdapter == null || !hasRequiredPermissions()) return
        try {
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
            _isScanning.value = false
            if (_connectionState.value == ConnectionState.SCANNING) {
                _connectionState.value = ConnectionState.IDLE
            }
            Log.d(TAG, "Stopped hardware discovery scanning.")
        } catch (e: Exception) {
            Log.e(TAG, "Error execution for stopDiscovery", e)
        }
    }

    private fun addDiscoveredDevice(device: DiscoveredDevice) {
        val currentList = _discoveredDevices.value.toMutableList()
        val index = currentList.indexOfFirst { it.macAddress == device.macAddress }
        if (index != -1) {
            currentList[index] = device
        } else {
            currentList.add(device)
        }
        _discoveredDevices.value = currentList
    }

    /**
     * Allows dynamic state updates on connection events manually inside SyncRoom workflows.
     */
    fun updateConnectionState(newState: ConnectionState) {
        _connectionState.value = newState
        Log.d(TAG, "Bluetooth connection state updated to: $newState")
    }

    /**
     * Helper string translator of device association statuses.
     */
    fun getBondStateString(bondState: Int): String {
        return when (bondState) {
            BluetoothDevice.BOND_BONDED -> "Paired"
            BluetoothDevice.BOND_BONDING -> "Pairing..."
            BluetoothDevice.BOND_NONE -> "Unpaired"
            else -> "Unknown"
        }
    }

    /**
     * Translates android.bluetooth states into SyncRoom standard ConnectionStates.
     */
    fun translateProfileConnectionState(profileState: Int): ConnectionState {
        return when (profileState) {
            BluetoothAdapter.STATE_CONNECTING -> ConnectionState.CONNECTING
            BluetoothAdapter.STATE_CONNECTED -> ConnectionState.CONNECTED
            BluetoothAdapter.STATE_DISCONNECTING -> ConnectionState.IDLE
            BluetoothAdapter.STATE_DISCONNECTED -> ConnectionState.DISCONNECTED
            else -> ConnectionState.IDLE
        }
    }
}
