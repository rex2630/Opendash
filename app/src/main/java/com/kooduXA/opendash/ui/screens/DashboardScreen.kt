package com.kooduXA.opendash.ui.screens

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kooduXA.opendash.data.protocol.DeviceStatus
import com.kooduXA.opendash.domain.model.CameraState
import com.kooduXA.opendash.domain.model.VideoFile

@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun DashboardScreen(
    onOpenSettings: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val files by viewModel.files.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val deviceStatus by viewModel.deviceStatus.collectAsStateWithLifecycle()
    val debugMessage by viewModel.debugMessage.collectAsStateWithLifecycle()

    val downloadProgressMap = remember { mutableStateMapOf<String, Float>() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
    ) {
        DashboardTopBar(
            onRefresh = { viewModel.refreshAll() },
            onOpenSettings = onOpenSettings
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
            }

            item {
                ConnectionStatusCard(
                    state = connectionState,
                    debugMessage = debugMessage,
                    onConnect = { viewModel.connect() },
                    onDisconnect = { viewModel.disconnect() }
                )
            }

            item {
                DeviceStatusCard(deviceStatus = deviceStatus)
            }

            if (isRefreshing) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF1E1E1E)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF00E676)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Refreshing camera data...",
                                color = Color.White
                            )
                        }
                    }
                }
            }

            item {
                SectionTitle("Recordings")
            }

            if (files.isEmpty()) {
                item {
                    EmptyFilesCard(connectionState = connectionState)
                }
            } else {
                items(files, key = { it.name }) { file ->
                    val progress = downloadProgressMap[file.name]

                    VideoFileCard(
                        file = file,
                        downloadProgress = progress,
                        onDownload = {
                            downloadProgressMap[file.name] = 0f
                            viewModel.downloadFile(
                                url = file.url,
                                filename = file.name,
                                onProgress = { progressValue ->
                                    downloadProgressMap[file.name] = progressValue
                                }
                            )
                        }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun DashboardTopBar(
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E))
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "OpenDash",
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )

        IconButton(onClick = onRefresh) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Refresh",
                tint = Color.White
            )
        }

        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Settings",
                tint = Color.White
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.R)
@Composable
private fun ConnectionStatusCard(
    state: CameraState,
    debugMessage: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val (title, color, buttonLabel, buttonAction, buttonEnabled) = when (state) {
        is CameraState.Disconnected -> {
            Quintuple(
                "Disconnected",
                Color(0xFFEF5350),
                "Connect",
                onConnect,
                true
            )
        }

        is CameraState.Scanning -> {
            Quintuple(
                "Scanning for camera",
                Color(0xFFFFC107),
                "Scanning...",
                {},
                false
            )
        }

        is CameraState.Connecting -> {
            Quintuple(
                "Connecting to camera",
                Color(0xFFFFC107),
                "Connecting...",
                {},
                false
            )
        }

        is CameraState.Connected -> {
            Quintuple(
                "Connected",
                Color(0xFF00E676),
                "Disconnect",
                onDisconnect,
                true
            )
        }

        is CameraState.Error -> {
            Quintuple(
                "Connection error",
                Color(0xFFEF5350),
                "Retry",
                onConnect,
                true
            )
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E1E1E)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when (state) {
                        is CameraState.Error -> Icons.Default.Error
                        else -> Icons.Default.Router
                    },
                    contentDescription = null,
                    tint = color
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = when (state) {
                    is CameraState.Error -> state.message
                    else -> debugMessage ?: "No status details"
                },
                color = Color.Gray,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = buttonAction,
                enabled = buttonEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(buttonLabel)
            }
        }
    }
}

@Composable
private fun DeviceStatusCard(
    deviceStatus: DeviceStatus
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E1E1E)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Device status",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(12.dp))

            StatusRow(
                icon = Icons.Default.FiberManualRecord,
                label = "Recording",
                value = if (deviceStatus.isRecording) "Yes" else "No",
                valueColor = if (deviceStatus.isRecording) Color(0xFF00E676) else Color.Gray
            )

            Spacer(modifier = Modifier.height(10.dp))

            StatusRow(
                icon = Icons.Default.SdStorage,
                label = "SD card",
                value = if (deviceStatus.hasSdCard) "Present" else "Missing",
                valueColor = if (deviceStatus.hasSdCard) Color(0xFF00E676) else Color(0xFFEF5350)
            )
        }
    }
}

@Composable
private fun StatusRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    valueColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.Gray,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            color = Color.White,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            color = valueColor,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SectionTitle(
    title: String
) {
    Text(
        text = title,
        color = Color(0xFF00E676),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
private fun EmptyFilesCard(
    connectionState: CameraState
) {
    val message = when (connectionState) {
        is CameraState.Connected -> "No recordings found on the camera."
        is CameraState.Connecting -> "Connecting to the camera..."
        is CameraState.Scanning -> "Scanning for available camera..."
        is CameraState.Error -> "Unable to load recordings because the connection failed."
        is CameraState.Disconnected -> "Connect to the camera to load recordings."
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E1E1E)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Videocam,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                color = Color.Gray,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun VideoFileCard(
    file: VideoFile,
    downloadProgress: Float?,
    onDownload: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable { },
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E1E1E)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = null,
                    tint = Color(0xFF00E676)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = file.name,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )

                    val details = buildString {
                        append(file.date)
                        if (file.size.isNotBlank()) {
                            append(" • ")
                            append(file.size)
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = details,
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                IconButton(onClick = onDownload) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = "Download file",
                        tint = Color.White
                    )
                }
            }

            if (downloadProgress != null) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { downloadProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private data class Quintuple<A, B, C, D, E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E
)
