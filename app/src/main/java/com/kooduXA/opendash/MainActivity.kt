/*
 * +-----------------------------------------------+
 * |   _                    _       __   __    _   |
 * |  | |                  | |      \\ \\ / /   / \\  |
 * |  | | __ ___   ___   __| |_   _  \\ V /   / _ \\ |
 * |  | |/ // _ \\ / _ \\ / _` | | | | /   \\  / ___ \\|
 * |  |   <| (_) | (_) | (_| | |_| |/ /^\\ \\/ /   \\ \\
 * |  |_|\\_\\\\___/ \\___/ \\__,_|\\__,_/\\/   \\/\\_|   |_|
 * |                                               |
 * +-----------------------------------------------+
 * OpenSourced by kooduXA
 */
package com.kooduXA.opendash

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.kooduXA.opendash.ui.screens.AppPermissionGate
import com.kooduXA.opendash.ui.screens.DashboardScreen
import com.kooduXA.opendash.ui.screens.DayColors
import com.kooduXA.opendash.ui.screens.DebugConsoleScreen
import com.kooduXA.opendash.ui.screens.EnhancedLocalGalleryScreen
import com.kooduXA.opendash.ui.screens.EnhancedRemoteBrowserWrapper
import com.kooduXA.opendash.ui.screens.SettingsScreen
import com.kooduXA.opendash.ui.theme.OpendashTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var permissionsGrantedState by mutableStateOf(false)
    private var permissionRequestedOnce by mutableStateOf(false)

    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            permissionRequestedOnce = true

            val allGranted = requiredPermissions().all { permission ->
                result[permission] == true || hasPermission(permission)
            }

            permissionsGrantedState = allGranted

            Log.d(TAG, "Permission result: $result")
            Log.d(TAG, "All required permissions granted: $allGranted")
        }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionsGrantedState = hasAllRequiredPermissions()

        setContent {
            OpendashTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OpenDashRoot(
                        permissionsGranted = permissionsGrantedState,
                        permissionRequestedOnce = permissionRequestedOnce,
                        missingPermissions = getMissingPermissions(),
                        onRequestPermissions = { requestRequiredPermissions() },
                        onContinueWithoutPermissions = {
                            Log.w(TAG, "Continuing without all recommended permissions")
                        }
                    )
                }
            }
        }
    }

    private fun requestRequiredPermissions() {
        val missing = getMissingPermissions()
        if (missing.isEmpty()) {
            permissionsGrantedState = true
            return
        }

        Log.d(TAG, "Requesting permissions: $missing")
        permissionsLauncher.launch(missing.toTypedArray())
    }

    private fun hasAllRequiredPermissions(): Boolean {
        return requiredPermissions().all { hasPermission(it) }
    }

    private fun getMissingPermissions(): List<String> {
        return requiredPermissions().filterNot { hasPermission(it) }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissions += Manifest.permission.ACCESS_COARSE_LOCATION
            }
        }

        return permissions.distinct()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun OpenDashRoot(
    permissionsGranted: Boolean,
    permissionRequestedOnce: Boolean,
    missingPermissions: List<String>,
    onRequestPermissions: () -> Unit,
    onContinueWithoutPermissions: () -> Unit
) {
    if (permissionsGranted) {
        OpenDashApp(
            isDebugBuild = BuildConfig.DEBUG
        )
    } else {
        AppPermissionGate(
            missingPermissions = missingPermissions,
            permissionRequestedOnce = permissionRequestedOnce,
            onRequestPermissions = onRequestPermissions,
            onContinueAnyway = onContinueWithoutPermissions
        )
    }
}

@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun OpenDashApp(
    isDebugBuild: Boolean
) {
    var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Dashboard) }

    BackHandler(enabled = currentScreen != AppScreen.Dashboard) {
        currentScreen = AppScreen.Dashboard
    }

    when (currentScreen) {
        AppScreen.Dashboard -> {
            DashboardScreen(
                viewModel = hiltViewModel(),
                onOpenSettings = { currentScreen = AppScreen.Settings }
            )
        }

        AppScreen.Settings -> {
            SettingsScreen(
                onBack = { currentScreen = AppScreen.Dashboard },
                onOpenDebugConsole = { currentScreen = AppScreen.DebugConsole },
                showDeveloperSection = isDebugBuild
            )
        }

        AppScreen.DebugConsole -> {
            DebugConsoleScreen(
                onBack = { currentScreen = AppScreen.Settings }
            )
        }

        AppScreen.Gallery -> {
            EnhancedLocalGalleryScreen(
                onBack = { currentScreen = AppScreen.Dashboard }
            )
        }

        AppScreen.RemoteFiles -> {
            EnhancedRemoteBrowserWrapper(
                viewModel = hiltViewModel(),
                colors = DayColors,
                onBack = { currentScreen = AppScreen.Dashboard }
            )
        }
    }
}

sealed class AppScreen {
    object Dashboard : AppScreen()
    object Settings : AppScreen()
    object DebugConsole : AppScreen()
    object Gallery : AppScreen()
    object RemoteFiles : AppScreen()
}
