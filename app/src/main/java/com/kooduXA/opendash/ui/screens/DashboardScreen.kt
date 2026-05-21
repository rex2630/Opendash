package com.kooduXA.opendash.ui.screens

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kooduXA.opendash.R
import com.kooduXA.opendash.data.protocol.DeviceStatus
import com.kooduXA.opendash.domain.model.CameraState
import com.kooduXA.opendash.domain.model.VideoFile

@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun DashboardScreen(
    onOpenSettings: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val connectionState = viewModel.connectionState.collectAsStateWithLifecycle()
    val files = viewModel.files.collectAsStateWithLifecycle()
    val isRefreshing = viewModel.isRefreshing.collectAsStateWithLifecycle()
    val deviceStatus = viewModel.deviceStatus.collectAsStateWithLifecycle()
    val debugMessage = viewModel.debugMessage.collectAsStateWithLifecycle()

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
            item { Spacer(modifier = Modifier.height(8.dp)) }

            item {
                ConnectionStatusCard(
                    state = connectionState.value,
                    debugMessage = debugMessage.value,
                    onConnect = { viewModel.connect() },
                    onDisconnect = { viewModel.disconnect() }
                )
            }

            item {
                DeviceStatusCard(deviceStatus = deviceStatus.value)
            }

            if (isRefreshing.value) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
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
                                text = stringResource(R.string.dashboard_status_refreshing),
                                color = Color.White
                            )
                        }
                    }
                }
            }

            item {
                SectionTitle(stringResource(R.string.dashboard_section_recordings))
            }

            if (files.value.isEmpty()) {
                item {
                    EmptyFilesCard(connectionState = connectionState.value)
                }
            } else {
                items(files.value, key = { it.filename }) { file ->
                    val progress = downloadProgressMap[file.filename]

                    VideoFileCard(
                        file = file,
                        downloadProgress = progress,
                        onDownload = {
                            downloadProgressMap[file.filename] = 0f
                            viewModel.downloadFile(
                                url = file.downloadUrl,
                                filename = file.filename,
                                onProgress = { progressValue ->
                                    downloadProgressMap[file.filename] = progressValue
                                }
                            )
                        }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
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
            text = stringResource(R.string.dashboard_title),
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )

        IconButton(onClick = onRefresh) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = stringResource(R.string.dashboard_refresh),
                tint = Color.White
            )
        }

        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = stringResource(R.string.dashboard_settings),
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
    val title: String
    val color: Color
    val buttonLabel: String
    val buttonAction: () -> Unit
    val buttonEnabled: Boolean

    when (state) {
        is CameraState.Disconnected -> {
            title = stringResource(R.string.dashboard_connection_disconnected)
            color = Color(0xFFEF5350)
            buttonLabel = stringResource(R.string.dashboard_action_connect)
            buttonAction = onConnect
            buttonEnabled = true
        }
        is CameraState.Scanning -> {
            title = stringResource(R.string.dashboard_connection_scanning)
            color = Color(0xFFFFC107)
            buttonLabel = stringResource(R.string.dashboard_action_scanning)
            buttonAction = {}
            buttonEnabled = false
        }
        is CameraState.Connecting -> {
            title = stringResource(R.string.dashboard_connection_connecting)
            color = Color(0xFFFFC107)
            buttonLabel = stringResource(R.string.dashboard_action_connecting)
            buttonAction = {}
            buttonEnabled = false
        }
        is CameraState.Connected -> {
            title = stringResource(R.string.dashboard_connection_connected)
            color = Color(0xFF00E676)
            buttonLabel = stringResource(R.string.dashboard_action_disconnect)
            buttonAction = onDisconnect
            buttonEnabled = true
        }
        is CameraState.Error -> {
            title = stringResource(R.string.dashboard_connection_error)
            color = Color(0xFFEF5350)
            buttonLabel = stringResource(R.string.dashboard_action_retry)
            buttonAction = onConnect
            buttonEnabled = true
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
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
                    else -> debugMessage ?: stringResource(R.string.dashboard_status_no_details)
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
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.dashboard_device_status_title),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(12.dp))

            StatusRow(
                icon = Icons.Default.FiberManualRecord,
                label = stringResource(R.string.dashboard_device_status_recording),
                value = if (deviceStatus.isRecording) {
                    stringResource(R.string.dashboard_device_status_yes)
                } else {
                    stringResource(R.string.dashboard_device_status_no)
                },
                valueColor = if (deviceStatus.isRecording) Color(0xFF00E676) else Color.Gray
            )

            Spacer(modifier = Modifier.height(10.dp))

            StatusRow(
                icon = Icons.Default.SdStorage,
                label = stringResource(R.string.dashboard_device_status_sdcard),
                value = if (deviceStatus.hasSdCard) {
                    stringResource(R.string.dashboard_device_status_present)
                } else {
                    stringResource(R.string.dashboard_device_status_missing)
                },
                valueColor = if (deviceStatus.hasSdCard) Color(0xFF00E676) else Color(0xFFEF5350)
            )
        }
    }
}

@Composable
private fun StatusRow(
    icon: ImageVector,
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
private fun SectionTitle(title: String) {
    Text(
        text = title,
        color = Color(0xFF00E676),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
private fun EmptyFilesCard(connectionState: CameraState) {
    val message = when (connectionState) {
        is CameraState.Connected -> stringResource(R.string.dashboard_empty_connected)
        is CameraState.Connecting -> stringResource(R.string.dashboard_empty_connecting)
        is CameraState.Scanning -> stringResource(R.string.dashboard_empty_scanning)
        is CameraState.Error -> stringResource(R.string.dashboard_empty_error)
        is CameraState.Disconnected -> stringResource(R.string.dashboard_empty_disconnected)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
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
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.filename,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )

                    val details = buildString {
                        append(file.time)
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
                        contentDescription = stringResource(R.string.dashboard_download_file),
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
