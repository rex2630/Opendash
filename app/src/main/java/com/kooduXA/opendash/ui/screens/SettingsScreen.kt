package com.kooduXA.opendash.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kooduXA.opendash.BuildConfig
import com.kooduXA.opendash.R

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onOpenDebugConsole: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showResolutionDialog by remember { mutableStateOf(false) }
    var showWifiDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showCameraIpDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E))
                .padding(16.dp)
                .statusBarsPadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = stringResource(R.string.settings_back_button_content_description),
                    tint = Color.White
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(id = R.string.settings_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                SectionHeader(text = stringResource(R.string.settings_section_video_recording))
                SettingsGroup {
                    SettingsItem(
                        icon = Icons.Default.HighQuality,
                        title = stringResource(R.string.settings_resolution_title),
                        subtitle = settings.videoResolution,
                        onClick = { showResolutionDialog = true }
                    )
                    Divider(color = Color.DarkGray, thickness = 0.5.dp)
                    SwitchItem(
                        icon = Icons.Default.Loop,
                        title = stringResource(R.string.settings_loop_recording_title),
                        checked = settings.loopRecording,
                        onCheckedChange = { viewModel.toggleLoopRecording(it) }
                    )
                    Divider(color = Color.DarkGray, thickness = 0.5.dp)
                    SwitchItem(
                        icon = Icons.Default.Mic,
                        title = stringResource(R.string.settings_record_audio_title),
                        checked = settings.audioRecording,
                        onCheckedChange = { viewModel.toggleAudio(it) }
                    )
                }
            }

            item {
                SectionHeader(text = stringResource(R.string.settings_section_connectivity))
                SettingsGroup {
                    SettingsItem(
                        icon = Icons.Default.Wifi,
                        title = stringResource(R.string.settings_camera_wifi_title),
                        subtitle = settings.wifiSSID + stringResource(R.string.settings_camera_wifi_subtitle_tap_to_edit),
                        onClick = { showWifiDialog = true }
                    )
                    Divider(color = Color.DarkGray, thickness = 0.5.dp)

                    SettingsItem(
                        icon = Icons.Default.Language,
                        title = stringResource(R.string.settings_camera_ip_title),
                        subtitle = settings.cameraIp.ifBlank {
                            stringResource(R.string.settings_camera_ip_auto)
                        },
                        onClick = { showCameraIpDialog = true }
                    )
                    Divider(color = Color.DarkGray, thickness = 0.5.dp)

                    SwitchItem(
                        icon = Icons.Default.WifiTethering,
                        title = stringResource(R.string.settings_auto_connect_title),
                        subtitle = stringResource(R.string.settings_auto_connect_subtitle),
                        checked = settings.wifiAutoConnect,
                        onCheckedChange = { viewModel.toggleAutoConnect(it) }
                    )
                }
            }

            item {
                SectionHeader(text = stringResource(R.string.settings_section_system))
                SettingsGroup {
                    SettingsItem(
                        icon = Icons.Default.Restore,
                        title = stringResource(R.string.settings_reset_settings_title),
                        subtitle = stringResource(R.string.settings_reset_settings_subtitle),
                        onClick = { viewModel.resetSettings() },
                        textColor = Color(0xFFEF5350)
                    )
                }
            }

            if (BuildConfig.DEBUG) {
                item {
                    SectionHeader(text = "Developer")
                    SettingsGroup {
                        SettingsItem(
                            icon = Icons.Default.Code,
                            title = "Debug Console",
                            subtitle = "View internal logs and diagnostics",
                            onClick = onOpenDebugConsole
                        )
                    }
                }
            }

            item {
                SectionHeader(text = stringResource(R.string.about_section_title))

                SettingsGroup {
                    SettingsItem(
                        icon = Icons.Default.Description,
                        title = stringResource(R.string.about_app_button),
                        subtitle = stringResource(R.string.about_app_button_subtitle),
                        onClick = { showAboutDialog = true }
                    )
                    Divider(color = Color.DarkGray, thickness = 0.5.dp)

                    val githubUrl = stringResource(R.string.github_url)
                    SettingsItem(
                        icon = Icons.Default.Code,
                        title = stringResource(R.string.github_repo),
                        subtitle = stringResource(R.string.github_subtitle),
                        onClick = { openUrl(context, githubUrl) }
                    )
                    Divider(color = Color.DarkGray, thickness = 0.5.dp)

                    val emailAddress = stringResource(R.string.contact_email)
                    SettingsItem(
                        icon = Icons.Default.Email,
                        title = stringResource(R.string.contact_us),
                        subtitle = stringResource(R.string.contact_subtitle),
                        onClick = { sendEmail(context, emailAddress) }
                    )
                    Divider(color = Color.DarkGray, thickness = 0.5.dp)

                    SettingsItem(
                        icon = Icons.Default.Info,
                        title = stringResource(R.string.version_title),
                        subtitle = stringResource(R.string.version_subtitle),
                        onClick = { }
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    text = stringResource(R.string.app_developer_name),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    if (showResolutionDialog) {
        ResolutionSelectionDialog(
            current = settings.videoResolution,
            onSelect = {
                viewModel.updateResolution(it)
                showResolutionDialog = false
            },
            onDismiss = { showResolutionDialog = false }
        )
    }

    if (showWifiDialog) {
        WifiEditDialog(
            initialSsid = settings.wifiSSID,
            initialPass = settings.wifiPassword,
            onSave = { ssid, pass ->
                viewModel.updateWifi(ssid, pass)
                showWifiDialog = false
            },
            onDismiss = { showWifiDialog = false }
        )
    }

    if (showCameraIpDialog) {
        CameraIpDialog(
            initialIp = settings.cameraIp,
            onSave = { ip ->
                viewModel.updateCameraIp(ip)
                showCameraIpDialog = false
            },
            onDismiss = { showCameraIpDialog = false }
        )
    }

    if (showAboutDialog) {
        AboutDialog(
            onDismiss = { showAboutDialog = false }
        )
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun sendEmail(context: Context, email: String) {
    try {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$email")
            putExtra(
                Intent.EXTRA_SUBJECT,
                context.getString(R.string.email_subject_opendash_inquiry)
            )
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = Color(0xFF00E676),
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
    )
}

@Composable
fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
            .padding(vertical = 4.dp)
    ) {
        content()
    }
}

@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    textColor: Color = Color.White
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.Gray,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = textColor,
                style = MaterialTheme.typography.bodyLarge
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = Color.DarkGray
        )
    }
}

@Composable
fun SwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.Gray,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = Color.Gray,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF00E676)
            )
        )
    }
}

@Composable
fun ResolutionSelectionDialog(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        stringResource(R.string.settings_resolution_4k_30fps),
        stringResource(R.string.settings_resolution_2k_60fps),
        stringResource(R.string.settings_resolution_1080p_60fps),
        stringResource(R.string.settings_resolution_1080p_30fps),
        stringResource(R.string.settings_resolution_720p_30fps)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_dialog_select_resolution_title)) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = option == current, onClick = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(option)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel_button))
            }
        }
    )
}

@Composable
fun WifiEditDialog(
    initialSsid: String,
    initialPass: String,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var ssid by rememberSaveable { mutableStateOf(initialSsid) }
    var pass by rememberSaveable { mutableStateOf(initialPass) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_dialog_camera_wifi_setup_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = ssid,
                    onValueChange = { ssid = it },
                    label = { Text(stringResource(R.string.settings_dialog_ssid_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text(stringResource(R.string.settings_dialog_password_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = if (passwordVisible) {
                                    stringResource(R.string.settings_hide_password)
                                } else {
                                    stringResource(R.string.settings_show_password)
                                }
                            )
                        }
                    }
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(ssid.trim(), pass) }) {
                Text(stringResource(R.string.dialog_save_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel_button))
            }
        }
    )
}

@Composable
fun CameraIpDialog(
    initialIp: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var ip by rememberSaveable { mutableStateOf(initialIp) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_camera_ip_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.settings_camera_ip_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = { Text(stringResource(R.string.settings_camera_ip_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(ip.trim()) }) {
                Text(stringResource(R.string.dialog_save_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel_button))
            }
        }
    )
}

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.about_app_button)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.app_description),
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.3
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}
