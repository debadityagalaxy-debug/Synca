package com.example.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.database.Track
import com.example.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(
    track: Track?,
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    repeatMode: Boolean,
    shuffleMode: Boolean,
    volume: Float,
    isHost: Boolean,
    activeSyncedDriftMs: Long,
    latencyCorrectionEvents: String,
    manualLatencyOffset: Long,
    onManualLatencyOffsetChange: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (track == null) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    "Select a song from Library tab to play.",
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = if (isSystemInDarkTheme()) TextSecondaryDark else TextSecondaryLight,
                    textAlign = TextAlign.Center
                )
            }
            return
        }

        // 1. Reactive Glowing Cyber Art Vinyl rotating in playback
        Box(
            modifier = Modifier
                .weight(1.2f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            CircularCyberDeck(isPlaying = isPlaying)
        }

        // 2. Track description elements
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = track.title,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = track.artist,
                fontSize = 14.sp,
                color = if (isSystemInDarkTheme()) TextSecondaryDark else TextSecondaryLight,
                textAlign = TextAlign.Center
            )
        }

        // 3. Pulsing Audio Waveform Canvas visualizer!
        PulseNeonWaveform(isPlaying = isPlaying, modifier = Modifier.height(35.dp).fillMaxWidth())

        // 4. Progress bar slider
        Column(modifier = Modifier.fillMaxWidth()) {
            Slider(
                value = if (duration > 0) currentPosition.toFloat() else 0f,
                onValueChange = { onSeek(it.toLong()) },
                valueRange = 0f..(if (duration > 0) duration.toFloat() else 100f),
                colors = SliderDefaults.colors(
                    thumbColor = NeonPink,
                    activeTrackColor = NeonPurple,
                    inactiveTrackColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f)
                ),
                modifier = Modifier.testTag("track_progress_slider")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatMs(currentPosition),
                    fontSize = 11.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = if (isSystemInDarkTheme()) TextSecondaryDark else TextSecondaryLight
                )
                Text(
                    text = formatMs(duration),
                    fontSize = 11.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = if (isSystemInDarkTheme()) TextSecondaryDark else TextSecondaryLight
                )
            }
        }

        // 5. Media controller layout
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onToggleShuffle,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    Icons.Default.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (shuffleMode) NeonCyan else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            }

            IconButton(
                onClick = onPrevious,
                modifier = Modifier.size(46.dp)
            ) {
                Icon(Icons.Filled.SkipPrevious, "Previous", modifier = Modifier.size(28.dp))
            }

            // Big pulsing neon Play/Pause toggle button
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(NeonPurple, NeonPink)))
                    .clickable { onTogglePlay() }
                    .border(2.dp, Color.White.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "PlayPause",
                    tint = Color.White,
                    modifier = Modifier.size(34.dp).testTag("play_pause_button")
                )
            }

            IconButton(
                onClick = onNext,
                modifier = Modifier.size(46.dp)
            ) {
                Icon(Icons.Filled.SkipNext, "Next", modifier = Modifier.size(28.dp))
            }

            IconButton(
                onClick = onToggleRepeat,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    Icons.Default.Repeat,
                    contentDescription = "Repeat",
                    tint = if (repeatMode) NeonPink else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            }
        }

        // 6. Cross-device shared Volume slider
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Filled.VolumeMute,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )
            Slider(
                value = volume,
                onValueChange = onVolumeChange,
                modifier = Modifier.weight(1f).testTag("volume_slider"),
                colors = SliderDefaults.colors(
                    thumbColor = NeonCyan,
                    activeTrackColor = NeonCyan
                )
            )
            Icon(
                Icons.Filled.VolumeUp,
                contentDescription = null,
                tint = NeonCyan
            )
        }

        // 7. Micro-Sync Telemetry Deck (proving real latency alignments)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Drift: ${activeSyncedDriftMs}ms",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = if (Math.abs(activeSyncedDriftMs) < 30) NeonMint else NeonPink
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(NeonCyan.copy(alpha = 0.12f))
                            .padding(vertical = 2.dp, horizontal = 6.dp)
                    ) {
                        Text(
                            text = if (isHost) "Protocol Host" else "Receiver Client",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = NeonCyan
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Sync Stream: $latencyCorrectionEvents",
                    fontSize = 11.sp,
                    color = if (isSystemInDarkTheme()) TextSecondaryDark else TextSecondaryLight
                )

                if (!isHost) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Manual Latency Calibration (ms)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "-500",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                        )
                        Slider(
                            value = manualLatencyOffset.toFloat(),
                            onValueChange = { onManualLatencyOffsetChange(it.toLong()) },
                            valueRange = -500f..500f,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                                .testTag("latency_slider"),
                            colors = SliderDefaults.colors(
                                thumbColor = NeonPurple,
                                activeTrackColor = NeonPurple
                            )
                        )
                        Text(
                            text = "+500",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                        )
                    }
                    Text(
                        text = "Offset: ${manualLatencyOffset}ms",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = NeonPurple,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }
    }
}

@Composable
fun CircularCyberDeck(isPlaying: Boolean, modifier: Modifier = Modifier) {
    // Endless rota animation
    val infiniteTransition = rememberInfiniteTransition()
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    val activeRotation = if (isPlaying) rotation else 0f

    // Outer card matches bg-gradient-to-br from-[#D0BCFF] to-[#381E72] rounded-[40px]
    Box(
        modifier = modifier
            .size(230.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(40.dp))
            .background(Brush.linearGradient(listOf(SophisticatedPrimary, SophisticatedOnPrimary)))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        // Inner rotation vinyl groove area
        Box(
            modifier = Modifier
                .fillMaxSize()
                .rotate(activeRotation)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.82f))
                .border(2.dp, Color.White.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            // Draw physical vinyl grooves inside canvas
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerOffset = Offset(size.width / 2, size.height / 2)
                drawCircle(color = Color(0xFF1E1B4B).copy(alpha = 0.6f), radius = size.width / 2.3f)
                drawCircle(color = Color(0xFF312E81).copy(alpha = 0.7f), radius = size.width / 2.8f)
                drawCircle(color = Color(0xFF4338CA).copy(alpha = 0.8f), radius = size.width / 3.5f)
                drawCircle(color = Color(0xFF111827).copy(alpha = 0.9f), radius = size.width / 4.5f)
            }

            // Inner glowing core logo label
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(SophisticatedSecondary, SophisticatedPurpleMedium))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Radio,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
fun PulseNeonWaveform(isPlaying: Boolean, modifier: Modifier = Modifier) {
    // Wave pulse generator
    val transition = rememberInfiniteTransition()
    val animFactor by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        )
    )
    
    val pulseRatio = if (isPlaying) animFactor else 0.2f

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val midY = height / 2

        val path = Path()
        path.moveTo(0f, midY)

        val segments = 25
        val segmentWidth = width / segments

        for (i in 0..segments) {
            val x = i * segmentWidth
            val isPeak = i % 2 == 1
            val amplitude = if (isPeak) (height * 0.45f) * pulseRatio else 0f
            val direction = if (i % 4 == 1) -1f else 1f
            val y = midY + (direction * amplitude)
            path.lineTo(x, y)
        }

        drawPath(
            path = path,
            brush = Brush.linearGradient(listOf(NeonPurple, NeonCyan)),
            style = Stroke(width = 4f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        )
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSecs = ms / 1000
    val mins = totalSecs / 60
    val secs = totalSecs % 60
    return String.format("%02d:%02d", mins, secs)
}
