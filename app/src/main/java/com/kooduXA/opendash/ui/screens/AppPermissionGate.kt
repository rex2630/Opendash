package com.kooduXA.opendash.ui.screens

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun AppPermissionGate(
    missingPermissions: List<String>,
    permissionRequestedOnce: Boolean,
    onRequestPermissions: () -> Unit,
    onContinueAnyway: () -> Unit
) {
    val context = LocalContext.current

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "Wi‑Fi device access is required",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = buildPermissionMessage(
                    missingPermissions = missingPermissions,
                    permissionRequestedOnce = permissionRequestedOnce
                ),
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onRequestPermissions,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Grant permissions")
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open Wi‑Fi settings")
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.parse("package:${context.packageName}")
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open app settings")
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onContinueAnyway,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Continue anyway")
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = buildDebugHint(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun buildPermissionMessage(
    missingPermissions: List<String>,
    permissionRequestedOnce: Boolean
): String {
    val permissionList = missingPermissions.joinToString()

    return if (!permissionRequestedOnce) {
        "The app needs permissions to communicate with the Wi‑Fi camera. " +
            "Without them, it may not be able to find the camera or connect to it.\n\n" +
            "Missing permissions: $permissionList"
    } else {
        "Permissions have not been granted yet. On newer Android versions, " +
            "access to Wi‑Fi devices often does not work properly without them.\n\n" +
            "Missing permissions: $permissionList"
    }
}

private fun buildDebugHint(): String {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        "Android 13+ usually requires Nearby Wi‑Fi Devices permission. " +
            "If you are using a Samsung phone, also make sure the device really stays connected to the camera's Wi‑Fi."
    } else {
        "On older Android versions, location permission may also be required " +
            "for working with Wi‑Fi devices. Also make sure you are actually connected to the camera's Wi‑Fi."
    }
}
