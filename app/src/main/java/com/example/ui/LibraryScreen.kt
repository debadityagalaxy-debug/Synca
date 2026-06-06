package com.example.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.database.Track
import com.example.ui.theme.*

@Composable
fun LibraryScreen(
    tracks: List<Track>,
    currentTrackIndex: Int,
    isPlaying: Boolean,
    onNavigateToTrack: (Int) -> Unit,
    onImportTrack: (String, String, String, Long) -> Unit,
    onDeleteTrack: (Track) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // SAF File Picker implementation for standard local audio files!
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                // Resolve metadata safely
                val metadata = resolveMediaMetadata(context, uri)
                onImportTrack(
                    metadata.title,
                    metadata.artist,
                    uri.toString(),
                    metadata.duration
                )
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        // Upper Controls Anchor
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "Media Room Library",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    "${tracks.size} tracks currently loaded",
                    fontSize = 12.sp,
                    color = if (androidx.compose.foundation.isSystemInDarkTheme()) TextSecondaryDark else TextSecondaryLight
                )
            }

            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        // Filter for mp3, wav, aac, flac, m4a formats
                        type = "audio/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                            "audio/mpeg",   // MP3
                            "audio/x-wav",  // WAV
                            "audio/wav",    // WAV-2
                            "audio/aac",    // AAC
                            "audio/flac",   // FLAC
                            "audio/mp4",    // M4A
                            "audio/x-m4a"   // M4A-2
                        ))
                    }
                    try {
                        filePickerLauncher.launch(intent)
                    } catch (e: Exception) {
                        Log.e("LibraryScreen", "Failed launching file picker file list", e)
                    }
                },
                modifier = Modifier.testTag("import_audio_btn"),
                colors = ButtonDefaults.buttonColors(containerColor = NeonPurple),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Filled.Add, "Import")
                Spacer(modifier = Modifier.width(4.dp))
                Text("Select File")
            }
        }

        // Active List View
        if (tracks.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Filled.LibraryMusic,
                        contentDescription = null,
                        tint = NeonPurple.copy(alpha = 0.5f),
                        modifier = Modifier.size(52.dp)
                    )
                    Text(
                        "No music files available.",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        "Connect your hardware memory or trigger adding high-fidelity synth presets instantly.",
                        fontSize = 12.sp,
                        color = if (androidx.compose.foundation.isSystemInDarkTheme()) TextSecondaryDark else TextSecondaryLight,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(tracks) { index, track ->
                    LibraryTrackRow(
                        track = track,
                        index = index,
                        isActive = index == currentTrackIndex,
                        isPlaying = isPlaying,
                        onRowClick = { onNavigateToTrack(index) },
                        onDelete = { onDeleteTrack(track) }
                    )
                }
            }
        }
    }
}

@Composable
fun LibraryTrackRow(
    track: Track,
    index: Int,
    isActive: Boolean,
    isPlaying: Boolean,
    onRowClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onRowClick() }
            .testTag("track_row_$index"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface
        ),
        border = if (isActive) BorderStroke(1.2.dp, NeonPurple) else null
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Visual Artwork ID Placeholder
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isActive) {
                            Brush.linearGradient(listOf(NeonPurple, NeonPink))
                        } else {
                            Brush.linearGradient(listOf(Color(0xFF312E81), Color(0xFF1E1B4B)))
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isActive && isPlaying) {
                    Icon(Icons.Filled.VolumeUp, "Playing", tint = Color.White, modifier = Modifier.size(20.dp))
                } else {
                    Icon(Icons.Filled.MusicNote, "Music", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    fontSize = 12.sp,
                    color = if (androidx.compose.foundation.isSystemInDarkTheme()) TextSecondaryDark else TextSecondaryLight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = formatMs(track.duration),
                    fontSize = 11.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = if (androidx.compose.foundation.isSystemInDarkTheme()) TextSecondaryDark else TextSecondaryLight
                )

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp).testTag("delete_track_btn_$index")
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete",
                        tint = Color.Red.copy(alpha = 0.65f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

// Media metadata resolver parsing Android Content Resolver names
private fun resolveMediaMetadata(context: Context, uri: Uri): MediaMetadata {
    var title = "Custom Track"
    var artist = "Local File"
    var duration = 180000L // default fallback: 3 minutes

    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                val displayName = if (nameIndex != -1) cursor.getString(nameIndex) else "track.mp3"
                title = displayName.removeSuffix(".mp3").removeSuffix(".wav").removeSuffix(".flac").removeSuffix(".m4a")
                
                // Estimate size-directed baseline duration
                if (sizeIndex != -1) {
                    val bytesSize = cursor.getLong(sizeIndex)
                    // Roughly 1MB per minute of 128kbps stereo MP3
                    val estimatedMinutes = (bytesSize / (1024 * 1024)).coerceIn(1, 10)
                    duration = estimatedMinutes * 60 * 1000L
                }
            }
        }
    } catch (e: Exception) {
        Log.e("MetadataResolver", "Error reading metadata", e)
    }

    return MediaMetadata(title, artist, duration)
}

private data class MediaMetadata(val title: String, val artist: String, val duration: Long)

private fun formatMs(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSecs = ms / 1000
    val mins = totalSecs / 60
    val secs = totalSecs % 60
    return String.format("%02d:%02d", mins, secs)
}
