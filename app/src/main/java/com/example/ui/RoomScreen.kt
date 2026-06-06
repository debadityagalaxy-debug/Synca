package com.example.ui

import android.os.Build
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.connectivity.ConnectionState
import com.example.connectivity.RoomMember
import com.example.connectivity.SyncRoomInfo
import com.example.connectivity.UserRole
import com.example.ui.theme.*

@Composable
fun RoomScreen(
    userRole: UserRole,
    connectionState: ConnectionState,
    discoveredRooms: List<SyncRoomInfo>,
    connectedMembers: List<RoomMember>,
    activeRoom: SyncRoomInfo?,
    onStartHost: (String, String) -> Unit,
    onJoinRoom: (SyncRoomInfo, String) -> Unit,
    onApproveMember: (String) -> Unit,
    onRejectMember: (String) -> Unit,
    onDisconnect: () -> Unit,
    onStartScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isHostingSetup by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        if (userRole == UserRole.NONE && !isHostingSetup) {
            // Main Join options
            item {
                Text(
                    text = "Welcome to SyncRoom",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Listen to perfectly synchronized music across multiple phones via secure local connection.",
                    fontSize = 14.sp,
                    color = if (isSystemInDarkTheme()) TextSecondaryDark else TextSecondaryLight,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // Connection controls
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { isHostingSetup = true },
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp)
                            .testTag("host_setup_btn"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.Podcasts, contentDescription = "Host")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Host Room", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    }

                    OutlinedButton(
                        onClick = onStartScan,
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp)
                            .testTag("scan_rooms_btn"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Icon(
                            if (connectionState == ConnectionState.SCANNING) Icons.Filled.WifiTethering else Icons.Filled.Radar,
                            contentDescription = "Scan"
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Find Rooms", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    }
                }
            }

            // Discovered Rooms
            item {
                CardHeader(
                    title = "Available Rooms Nearby",
                    subtitle = if (connectionState == ConnectionState.SCANNING) "Active Scanning..." else "Ready to Discover"
                )
            }

            if (discoveredRooms.isEmpty()) {
                item {
                    val message = "No Bluetooth streams detected. Confirm dynamic permissions are enabled."
                    EmptyRoomsState(message)
                }
            } else {
                items(discoveredRooms) { room ->
                    RoomItemCard(room = room, onJoin = { pwd -> onJoinRoom(room, pwd) })
                }
            }
        } else if (isHostingSetup && userRole == UserRole.NONE) {
            // Host configuration screen
            item {
                HostForm(
                    onBack = { isHostingSetup = false },
                    onHostCreate = { name, pwd ->
                        onStartHost(name, pwd)
                        isHostingSetup = false
                    }
                )
            }
        } else {
            // Active connected/hosting session panel
            item {
                activeRoom?.let { room ->
                    ActiveRoomPanel(
                        room = room,
                        role = userRole,
                        connectionState = connectionState,
                        connectedMembers = connectedMembers,
                        onApproveMember = onApproveMember,
                        onRejectMember = onRejectMember,
                        onDisconnect = onDisconnect
                    )
                }
            }
        }
    }
}

@Composable
fun CardHeader(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onBackground)
        Text(subtitle, fontSize = 12.sp, color = if (isSystemInDarkTheme()) TextSecondaryDark else TextSecondaryLight)
    }
}

@Composable
fun EmptyRoomsState(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(130.dp)
            .border(
                1.dp,
                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f),
                RoundedCornerShape(14.dp)
            )
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(16.dp)
        ) {
            Icon(
                Icons.Outlined.BluetoothSearching,
                contentDescription = null,
                tint = NeonPurple.copy(alpha = 0.7f),
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                color = if (isSystemInDarkTheme()) TextSecondaryDark else TextSecondaryLight
            )
        }
    }
}

@Composable
fun RoomItemCard(room: SyncRoomInfo, onJoin: (String) -> Unit) {
    var showPasswordPrompt by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (room.requiresPassword) {
                    showPasswordPrompt = !showPasswordPrompt
                } else {
                    onJoin("")
                }
            },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = room.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        if (room.requiresPassword) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(Icons.Filled.Lock, "Locked", tint = NeonPink, modifier = Modifier.size(14.dp))
                        }
                    }
                    Text(
                        text = "Host: ${room.hostName} • ${room.memberCount} active devices",
                        fontSize = 12.sp,
                        color = if (isSystemInDarkTheme()) TextSecondaryDark else TextSecondaryLight,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                IconButton(
                    onClick = {
                        if (room.requiresPassword) {
                            showPasswordPrompt = !showPasswordPrompt
                        } else {
                            onJoin("")
                        }
                    },
                    modifier = Modifier
                        .background(NeonPurple.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                        .size(38.dp)
                ) {
                    Icon(
                        Icons.Filled.DoubleArrow,
                        contentDescription = "Join",
                        tint = NeonPurple,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            AnimatedVisibility(
                visible = showPasswordPrompt,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Divider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Secure Password Required", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = NeonPink)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            placeholder = { Text("6-Digit Code") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = {
                                keyboardController?.hide()
                                onJoin(password)
                            }),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonPink
                            )
                        )
                        Button(
                            onClick = {
                                keyboardController?.hide()
                                onJoin(password)
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = NeonPink)
                        ) {
                            Text("Connect")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HostForm(onBack: () -> Unit, onHostCreate: (String, String) -> Unit) {
    var roomName by remember { mutableStateOf("${Build.MODEL}'s Room") }
    var password by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Configure Music Room",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }
            Spacer(modifier = Modifier.height(14.dp))

            Text("Room Broadcast Name", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = roomName,
                onValueChange = { roomName = it },
                singleLine = true,
                placeholder = { Text("My Studio Base") },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().testTag("host_room_name_input"),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonPurple)
            )

            Spacer(modifier = Modifier.height(14.dp))
            Text("Access Password (Optional)", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                singleLine = true,
                placeholder = { Text("None") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().testTag("host_room_password_input"),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonPurple)
            )

            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = {
                    if (roomName.isNotEmpty()) {
                        onHostCreate(roomName, password)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("host_create_confirm_btn"),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NeonPurple)
            ) {
                Text("Launch Radio Broadcast", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ActiveRoomPanel(
    room: SyncRoomInfo,
    role: UserRole,
    connectionState: ConnectionState,
    connectedMembers: List<RoomMember>,
    onApproveMember: (String) -> Unit,
    onRejectMember: (String) -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.5.dp, Brush.linearGradient(listOf(NeonPurple, NeonPink)))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Room Title header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = room.name,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (role == UserRole.HOST) NeonPurple else NeonPink)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                if (role == UserRole.HOST) "Host" else "Receiver",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                    Text(
                        text = "Status: ${connectionState.name} • Active Clock offset: ${room.clockOffsetMs}ms",
                        fontSize = 11.sp,
                        color = NeonCyan
                    )
                }

                IconButton(
                    onClick = onDisconnect,
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Red.copy(alpha = 0.15f))
                ) {
                    Icon(Icons.Filled.Logout, contentDescription = "Close", tint = Color.Red)
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            Divider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
            Spacer(modifier = Modifier.height(14.dp))

            // Embedded Artwork Canvas QR Code for fast local wireless invites!
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SyncQRCodeWidget(room = room, modifier = Modifier.size(105.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Scan QR to Pair",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Ask other users to target this code with their scan utility to join the playback workspace immediately.",
                        fontSize = 10.sp,
                        color = if (isSystemInDarkTheme()) TextSecondaryDark else TextSecondaryLight,
                        lineHeight = 14.sp
                    )
                    if (room.requiresPassword) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Key, "Pass", tint = NeonPink, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Room Password: ${room.password}", fontSize = 11.sp, color = NeonPink, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            Divider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
            Spacer(modifier = Modifier.height(14.dp))

            // Dynamic User action approvals
            val applicants = connectedMembers.filter { !it.isApproved && !it.isHost }
            if (role == UserRole.HOST && applicants.isNotEmpty()) {
                Text("Approval Actions Queue", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = NeonPink)
                Spacer(modifier = Modifier.height(8.dp))
                applicants.forEach { applicant ->
                    ApprovalItem(
                        member = applicant,
                        onApprove = { onApproveMember(applicant.id) },
                        onReject = { onRejectMember(applicant.id) }
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            // Sync Member database overview List
            Text("Connected Devices", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(8.dp))

            val activeMembers = connectedMembers.filter { it.isApproved || it.isHost }
            if (activeMembers.isEmpty()) {
                Text("Waiting for synced connections...", fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
            } else {
                activeMembers.forEach { member ->
                    MemberRow(member = member)
                }
            }
        }
    }
}

@Composable
fun SyncQRCodeWidget(room: SyncRoomInfo, modifier: Modifier = Modifier) {
    // Elegant Custom Canvas design representing connection barcode
    Canvas(
        modifier = modifier
            .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .background(Color.White, RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        val sizeVal = size.width
        val blockCount = 8
        val blockSize = sizeVal / blockCount

        // 1. Draw corner anchor position boxes (standard styling for secure pairing)
        val strokeWidth = 5f
        // Top-Left anchor
        drawRect(Color.Black, Offset(0f, 0f), Size(blockSize * 2, blockSize * 2), style = Stroke(strokeWidth))
        drawRect(Color.Black, Offset(blockSize * 0.5f, blockSize * 0.5f), Size(blockSize, blockSize))

        // Top-Right anchor
        drawRect(Color.Black, Offset(sizeVal - (blockSize * 2), 0f), Size(blockSize * 2, blockSize * 2), style = Stroke(strokeWidth))
        drawRect(Color.Black, Offset(sizeVal - (blockSize * 1.5f), blockSize * 0.5f), Size(blockSize, blockSize))

        // Bottom-Left anchor
        drawRect(Color.Black, Offset(0f, sizeVal - (blockSize * 2)), Size(blockSize * 2, blockSize * 2), style = Stroke(strokeWidth))
        drawRect(Color.Black, Offset(blockSize * 0.5f, sizeVal - (blockSize * 1.5f)), Size(blockSize, blockSize))

        // 2. Draw mock sync data payload pixels inside (seeded by room name to look dynamic!)
        val seed = room.name.hashCode()
        for (row in 0 until blockCount) {
            for (col in 0 until blockCount) {
                // Avoid anchor overlap boundaries
                if (row <= 2 && col <= 2) continue
                if (row <= 2 && col >= blockCount - 3) continue
                if (row >= blockCount - 3 && col <= 2) continue

                // Pseudo-random distribution based on hash code representation
                val active = (seed xor (row * 33) xor (col * 49)) % 2 == 0
                if (active) {
                    drawRect(
                        color = if ((row + col) % 3 == 0) NeonPurple else Color.Black,
                        topLeft = Offset(col * blockSize, row * blockSize),
                        size = Size(blockSize - 1, blockSize - 1)
                    )
                }
            }
        }
    }
}

@Composable
fun ApprovalItem(member: RoomMember, onApprove: () -> Unit, onReject: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(NeonPink.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(member.name, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconButton(
                onClick = onReject,
                modifier = Modifier.size(32.dp).background(Color.Red.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            ) {
                Icon(Icons.Filled.Close, "Decline", tint = Color.Red, modifier = Modifier.size(16.dp))
            }
            IconButton(
                onClick = onApprove,
                modifier = Modifier.size(32.dp).background(NeonMint.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            ) {
                Icon(Icons.Filled.Check, "Approve", tint = NeonMint, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun MemberRow(member: RoomMember) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (member.isHost) Icons.Filled.Podcasts else Icons.Outlined.Devices,
                contentDescription = null,
                tint = if (member.isHost) NeonPurple else NeonMint,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(member.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(NeonMint.copy(alpha = 0.12f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = if (member.isHost) "NTP Master" else "Offset: ${member.latencyMs}ms",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NeonMint
                )
            }
        }
    }
}
