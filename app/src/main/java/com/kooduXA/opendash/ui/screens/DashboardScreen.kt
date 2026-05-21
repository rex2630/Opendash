package com.kooduXA.opendash.ui.screens

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdCardAlert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kooduXA.opendash.R
import com.kooduXA.opendash.VideoPlayer
import kotlinx.coroutines.delay

@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToGallery: () -> Unit,
    onNavigateToFiles: () -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()

    var areControlsVisible by remember { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(true) }

    val networkDebug by rememberNetworkDebugInfo()

    LaunchedEffect(showControls, isLandscape) {
        if (isLandscape && showControls) {
            delay(5000)
            showControls = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (isLandscape) {
            Box(modifier = Modifier.fillMaxSize()) {
                VideoContent(
                    uiState = uiState,
                    viewModel = viewModel,
                    modifier = Modifier
                        .fillMaxSize()
                        .align(Alignment.Center),
                    onTap = {
                        areControlsVisible = !areControlsVisible
                        if (areControlsVisible) showControls = true
                    }
                )

                AnimatedVisibility(
                    visible = areControlsVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(16.dp)
                                .statusBarsPadding(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StatusPill(
                                uiState = uiState,
                                viewModel = viewModel,
                                modifier = Modifier
                            )

                            NetworkDebugPill(
                                info = networkDebug,
                                onOpenWifiSettings = {
                                    context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                                }
                            )
                        }

                        if (isRecording) {
                            RecordingBadge(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(16.dp)
                                    .statusBarsPadding()
                            )
                        }

                        Column(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val isAudio by viewModel.isAudioEnabled.collectAsStateWithLifecycle()

                            GlassIconButton(
                                icon = if (isAudio) Icons.Default.Mic else Icons.Default.MicOff,
                                onClick = { viewModel.toggleAudio() },
                                tint = if (isAudio) Color.White else Color.Yellow
                            )

                            BigShutterButton(viewModel)

                            GlassIconButton(
                                icon = Icons.Rounded.PhotoCamera,
                                onClick = { viewModel.takePhoto() }
                            )
                        }

                        Column(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            SmallCircleButton(
                                icon = Icons.Rounded.Folder,
                                onClick = onNavigateToFiles
                            )

                            SmallCircleButton(
                                icon = Icons.Default.PhoneAndroid,
                                onClick = onNavigateToGallery
                            )

                            SmallCircleButton(
                                icon = Icons.Default.Settings,
                                onClick = onNavigateToSettings
                            )

                            SmallCircleButton(
                                icon = Icons.Default.Refresh,
                                onClick = { viewModel.connect() }
                            )
                        }
                    }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(0.55f)
                        .fillMaxWidth()
                        .background(Color.Black)
                ) {
                    VideoContent(
                        uiState = uiState,
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize(),
                        onTap = null
                    )

                    if (isRecording) {
                        RecordingBadge(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp)
                        )
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatusPill(
                            uiState = uiState,
                            viewModel = viewModel,
                            modifier = Modifier
                        )

                        NetworkDebugPill(
                            info = networkDebug,
                            onOpenWifiSettings = {
                                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                            }
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(0.45f)
                        .fillMaxWidth()
                        .background(Color(0xFF1E1E1E))
                ) {
                    PortraitControls(
                        viewModel = viewModel,
                        onSettings = onNavigateToSettings,
                        onGallery = onNavigateToGallery,
                        onFiles = onNavigateToFiles
                    )
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun VideoContent(
    uiState: DashboardUiState,
    viewModel: DashboardViewModel,
    modifier: Modifier,
    onTap: (() -> Unit)?
) {
    Box(
        modifier = modifier.then(
            if (onTap != null) {
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onTap() }
            } else {
                Modifier
            }
        ),
        contentAlignment = Alignment.Center
    ) {
        if (uiState is DashboardUiState.Streaming) {
            VideoPlayer(
                url = uiState.url,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            when (uiState) {
                is DashboardUiState.Loading -> {
                    CircularProgressIndicator(color = Color(0xFF00E676))
                }

                is DashboardUiState.Error -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.WifiOff, null, tint = Color.Red)
                        Text(uiState.message, color = Color.White)
                        Button(onClick = { viewModel.connect() }) {
                            Text(stringResource(R.string.dashboard_retry_button))
                        }
                    }
                }

                else -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.VideocamOff,
                            null,
                            tint = Color.Gray,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.dashboard_not_connected),
                            color = Color.Gray
                        )
                        Button(
                            onClick = { viewModel.connect() },
                            modifier = Modifier.padding(top = 16.dp)
                        ) {
                            Text(stringResource(R.string.dashboard_connect_to_camera_button))
                        }
                    }
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun PortraitControls(
    viewModel: DashboardViewModel,
    onSettings: () -> Unit,
    onGallery: () -> Unit,
    onFiles: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ControlItem(Icons.Rounded.Folder, stringResource(R.string.dashboard_files_button), onFiles)
            ControlItem(Icons.Default.Settings, stringResource(R.string.dashboard_settings_button), onSettings)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassIconButton(Icons.Rounded.PhotoCamera, onClick = { viewModel.takePhoto() })

            BigShutterButton(viewModel)

            val isAudio by viewModel.isAudioEnabled.collectAsStateWithLifecycle()
            GlassIconButton(
                if (isAudio) Icons.Default.Mic else Icons.Default.MicOff,
                onClick = { viewModel.toggleAudio() },
                tint = if (isAudio) Color.White else Color.Yellow
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ControlItem(Icons.Rounded.PhoneAndroid, stringResource(R.string.dashboard_local_gallery_button), onGallery)
            ControlItem(Icons.Default.Refresh, stringResource(R.string.dashboard_reconnect_button), { viewModel.connect() })
        }
    }
}

@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun LandscapeHud(
    viewModel: DashboardViewModel,
    uiState: DashboardUiState,
    onSettings: () -> Unit,
    onGallery: () -> Unit,
    onFiles: () -> Unit
) {
    Row(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            StatusPill(uiState, viewModel, Modifier.padding(16.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(100.dp)
                .background(Color.Black.copy(alpha = 0.8f))
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            GlassIconButton(Icons.Default.Settings, onClick = onSettings)
            GlassIconButton(Icons.Rounded.Folder, onClick = onFiles)
            BigShutterButton(viewModel)
            GlassIconButton(Icons.Rounded.PhotoCamera, onClick = { viewModel.takePhoto() })
            GlassIconButton(Icons.Rounded.PhoneAndroid, onClick = onGallery)
        }
    }
}

@Composable
fun ControlItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Icon(icon, null, tint = Color.LightGray, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.Gray, style = MaterialTheme.typography.labelSmall)
    }
}

@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun BigShutterButton(viewModel: DashboardViewModel) {
    val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current

    val infiniteTransition = rememberInfiniteTransition(label = "PulseTransition")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "PulseScale"
    )

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "PulseAlpha"
    )

    Box(contentAlignment = Alignment.Center) {
        if (isRecording) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .scale(pulseScale)
                    .background(Color.Red.copy(alpha = pulseAlpha), CircleShape)
            )
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(80.dp)
                .border(4.dp, if (isRecording) Color.Red else Color.White, CircleShape)
                .clip(CircleShape)
                .background(if (isRecording) Color.Transparent else Color.Black.copy(alpha = 0.3f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true, color = Color.Red),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.toggleRecording()
                    }
                )
        ) {
            if (isRecording) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color.Red, RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(Color.Red, CircleShape)
                )
            }
        }
    }
}

@Composable
fun GlassIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    tint: Color = Color.White
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(50.dp)
            .clip(CircleShape)
            .background(Color.DarkGray.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
    }
}

@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun StatusPill(
    uiState: DashboardUiState,
    viewModel: DashboardViewModel,
    modifier: Modifier
) {
    val hasSdCard by viewModel.hasSdCard.collectAsStateWithLifecycle()
    val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()
    val duration by viewModel.recordingDuration.collectAsStateWithLifecycle()

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val (color, text) = when (uiState) {
            is DashboardUiState.Streaming -> Color(0xFF00E676) to stringResource(R.string.dashboard_status_live)
            is DashboardUiState.Loading -> Color.Yellow to stringResource(R.string.dashboard_status_scan)
            is DashboardUiState.Error -> Color.Red to stringResource(R.string.dashboard_status_error)
            else -> Color.Gray to stringResource(R.string.dashboard_status_idle)
        }

        Box(
            Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.labelSmall)

        if (!hasSdCard) {
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Default.SdCardAlert, null, tint = Color.Red, modifier = Modifier.size(16.dp))
        }

        if (isRecording) {
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .size(8.dp)
                    .background(Color.Red, CircleShape)
            )
            Spacer(Modifier.width(4.dp))
            Text(duration, color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun RecordingBadge(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "RecBlink")

    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "RecAlpha"
    )

    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(Color.Red.copy(alpha = alpha), CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.dashboard_recording_badge),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            letterSpacing = 2.sp
        )
    }
}

@Composable
fun SmallCircleButton(icon: ImageVector, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color(0xFF1E1E1E))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = Color.White)
    }
}

@Composable
fun NetworkDebugPill(
    info: NetworkDebugInfo,
    onOpenWifiSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onOpenWifiSettings)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = "Network: ${info.summary}",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = info.detail,
            color = Color.LightGray,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
fun rememberNetworkDebugInfo(): State<NetworkDebugInfo> {
    val context = LocalContext.current

    return produceState(initialValue = getNetworkDebugInfo(context), context) {
        while (true) {
            value = getNetworkDebugInfo(context)
            delay(2000)
        }
    }
}

data class NetworkDebugInfo(
    val summary: String,
    val detail: String
)

fun getNetworkDebugInfo(context: Context): NetworkDebugInfo {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val activeNetwork = cm.activeNetwork
        ?: return NetworkDebugInfo(
            summary = "No active network",
            detail = "Phone is not connected to any active network."
        )

    val caps = cm.getNetworkCapabilities(activeNetwork)
        ?: return NetworkDebugInfo(
            summary = "Unknown",
            detail = "Could not load NetworkCapabilities."
        )

    val transports = buildList {
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi‑Fi")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("Cellular")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("Ethernet")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
    }.ifEmpty { listOf("Other") }

    val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

    val summary = transports.joinToString(" + ")
    val detail = "internet=$internet, validated=$validated"

    return NetworkDebugInfo(
        summary = summary,
        detail = detail
    )
}
