package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.connectivity.ConnectionState
import com.example.connectivity.UserRole
import com.example.connectivity.SyncRoomInfo
import com.example.ui.LibraryScreen
import com.example.ui.PlayerScreen
import com.example.ui.RoomScreen
import com.example.ui.theme.*
import com.example.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                val viewModel: MainViewModel = viewModel()
                MainDashboard(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainDashboard(viewModel: MainViewModel) {
    val context = LocalContext.current
    var activeTab by remember { mutableIntStateOf(1) } // Default to Now Playing tab first!

    // State Collection
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val currentPosition by viewModel.currentPosition.collectAsStateWithLifecycle()
    val duration by viewModel.duration.collectAsStateWithLifecycle()
    val currentTrackIndex by viewModel.currentTrackIndex.collectAsStateWithLifecycle()
    val repeatMode by viewModel.repeatMode.collectAsStateWithLifecycle()
    val shuffleMode by viewModel.shuffleMode.collectAsStateWithLifecycle()
    val volume by viewModel.volume.collectAsStateWithLifecycle()
    val activeSyncedDriftMs by viewModel.activeSyncedDriftMs.collectAsStateWithLifecycle()
    val latencyCorrectionEvents by viewModel.latencyCorrectionEvents.collectAsStateWithLifecycle()

    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val userRole by viewModel.userRole.collectAsStateWithLifecycle()
    val connectedMembers by viewModel.connectedMembers.collectAsStateWithLifecycle()
    val discoveredRooms by viewModel.discoveredRooms.collectAsStateWithLifecycle()
    val activeRoom by viewModel.activeRoom.collectAsStateWithLifecycle()

    // Permissions orchestration
    var hasPermissions by remember { mutableStateOf(false) }
    val requiredPermissions = remember {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissions.toTypedArray()
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermissions = results.values.all { it }
        Log.d("SyncRoom", "Permissions result: $hasPermissions")
    }

    LaunchedEffect(Unit) {
        val allGranted = requiredPermissions.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            hasPermissions = true
        } else {
            launcher.launch(requiredPermissions)
        }
    }

    // Resolve active playing track
    val activeTrack = remember(tracks, currentTrackIndex) {
        if (currentTrackIndex in tracks.indices) tracks[currentTrackIndex] else null
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        bottomBar = {
            // High fidelity Sophisticated Dark bottom navigation
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.background,
                windowInsets = WindowInsets.navigationBars,
                modifier = Modifier.background(MaterialTheme.colorScheme.background)
            ) {
                NavigationBarItem(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    icon = {
                        Icon(
                            imageVector = if (activeTab == 0) Icons.Filled.Podcasts else Icons.Outlined.Podcasts,
                            contentDescription = "Sockets"
                        )
                    },
                    label = { Text("Radio") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = SophisticatedPrimary,
                        selectedTextColor = SophisticatedPrimary,
                        unselectedIconColor = SophisticatedGreyMuted,
                        unselectedTextColor = SophisticatedGreyMuted,
                        indicatorColor = SophisticatedPurpleMedium.copy(alpha = 0.45f)
                    ),
                    modifier = Modifier.testTag("nav_broadcasting")
                )

                NavigationBarItem(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    icon = {
                        Icon(
                            imageVector = if (activeTab == 1) Icons.Filled.Radio else Icons.Outlined.Radio,
                            contentDescription = "Playing"
                        )
                    },
                    label = { Text("Playing") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = SophisticatedPrimary,
                        selectedTextColor = SophisticatedPrimary,
                        unselectedIconColor = SophisticatedGreyMuted,
                        unselectedTextColor = SophisticatedGreyMuted,
                        indicatorColor = SophisticatedPurpleMedium.copy(alpha = 0.45f)
                    ),
                    modifier = Modifier.testTag("nav_player")
                )

                NavigationBarItem(
                    selected = activeTab == 2,
                    onClick = { activeTab = 2 },
                    icon = {
                        Icon(
                            imageVector = if (activeTab == 2) Icons.Filled.LibraryMusic else Icons.Outlined.LibraryMusic,
                            contentDescription = "Library"
                        )
                    },
                    label = { Text("Library") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = SophisticatedPrimary,
                        selectedTextColor = SophisticatedPrimary,
                        unselectedIconColor = SophisticatedGreyMuted,
                        unselectedTextColor = SophisticatedGreyMuted,
                        indicatorColor = SophisticatedPurpleMedium.copy(alpha = 0.45f)
                    ),
                    modifier = Modifier.testTag("nav_library")
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Elegant top bar with connection badges
            TopAppBarWidget(
                userRole = userRole,
                state = connectionState,
                room = activeRoom
            )

            // Permissions alert header if blocked
            if (!hasPermissions) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Red.copy(alpha = 0.12f))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Bluetooth, location, and storage permissions required for local broadcast pairing.",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.Red,
                        modifier = Modifier.clickable { launcher.launch(requiredPermissions) }
                    )
                }
            }

            // Stateful Page Transitions
            Box(modifier = Modifier.weight(1f)) {
                when (activeTab) {
                    0 -> RoomScreen(
                        userRole = userRole,
                        connectionState = connectionState,
                        discoveredRooms = discoveredRooms,
                        connectedMembers = connectedMembers,
                        activeRoom = activeRoom,
                        onStartHost = { name, pwd -> viewModel.startHosting(name, pwd) },
                        onJoinRoom = { room, pwd -> viewModel.joinSelectedRoom(room, pwd) },
                        onApproveMember = { viewModel.bluetoothService.approveMember(it) },
                        onRejectMember = { viewModel.bluetoothService.rejectMember(it) },
                        onDisconnect = { viewModel.exitRoom() },
                        onStartScan = { viewModel.bluetoothService.startDiscovery() }
                    )

                    1 -> PlayerScreen(
                        track = activeTrack,
                        isPlaying = isPlaying,
                        currentPosition = currentPosition,
                        duration = duration,
                        repeatMode = repeatMode,
                        shuffleMode = shuffleMode,
                        volume = volume,
                        isHost = userRole == UserRole.HOST,
                        activeSyncedDriftMs = activeSyncedDriftMs,
                        latencyCorrectionEvents = latencyCorrectionEvents,
                        onTogglePlay = { viewModel.togglePlay() },
                        onNext = { viewModel.triggerNext() },
                        onPrevious = { viewModel.triggerPrevious() },
                        onSeek = { viewModel.triggerSeek(it) },
                        onVolumeChange = { viewModel.triggerVolumeChange(it) },
                        onToggleRepeat = { viewModel.toggleRepeat() },
                        onToggleShuffle = { viewModel.toggleShuffle() }
                    )

                    2 -> LibraryScreen(
                        tracks = tracks,
                        currentTrackIndex = currentTrackIndex,
                        isPlaying = isPlaying,
                        onNavigateToTrack = { index ->
                            viewModel.audioController.seekTo(0)
                            viewModel.audioController.setPlaylist(tracks)
                            viewModel.triggerSeek(0)
                        },
                        onImportTrack = { title, artist, path, dur ->
                            viewModel.importCustomTrack(title, artist, path, dur)
                        },
                        onDeleteTrack = { trackingItem ->
                            viewModel.deleteTrack(trackingItem)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun TopAppBarWidget(
    userRole: UserRole,
    state: ConnectionState,
    room: SyncRoomInfo?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            // Elegant upper uppercase indicator
            val upperIndicatorText = when {
                userRole != UserRole.NONE && state == ConnectionState.CONNECTED -> "Room Active"
                else -> "Transmitters Ready"
            }
            Text(
                text = upperIndicatorText.uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = SophisticatedPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (userRole != UserRole.NONE) SophisticatedMint else SophisticatedGreyMuted)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "SyncRoom Alpha",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                    color = Color.White
                )
            }
        }

        // Active Status Room ID badge based on HTML's bg-[#4F378B] and text-[#EADDFF] design pattern
        val badgeBg = if (userRole != UserRole.NONE) SophisticatedPurpleMedium else SophisticatedSurface
        val badgeTextColor = if (userRole != UserRole.NONE) SophisticatedPurpleLight else SophisticatedGreyMuted
        val badgeText = if (userRole != UserRole.NONE) {
            room?.name?.take(5)?.uppercase() ?: "#A7X2"
        } else {
            "#SHRD"
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Room badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(badgeBg)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = badgeText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = badgeTextColor,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}
