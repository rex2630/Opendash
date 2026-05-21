package com.kooduXA.opendash.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        val VIDEO_RESOLUTION = stringPreferencesKey("video_resolution")
        val VIDEO_QUALITY = stringPreferencesKey("video_quality")
        val FRAME_RATE = intPreferencesKey("frame_rate")
        val BITRATE = stringPreferencesKey("bitrate")
        val CODEC = stringPreferencesKey("codec")

        val LOOP_RECORDING = booleanPreferencesKey("loop_recording")
        val LOOP_DURATION = intPreferencesKey("loop_duration")
        val AUTO_DELETE_OLDEST = booleanPreferencesKey("auto_delete_oldest")
        val PRE_BUFFER_RECORDING = booleanPreferencesKey("pre_buffer_recording")
        val PRE_BUFFER_SECONDS = intPreferencesKey("pre_buffer_seconds")

        val G_SENSOR_SENSITIVITY = stringPreferencesKey("g_sensor_sensitivity")
        val IMPACT_DETECTION = booleanPreferencesKey("impact_detection")
        val PARKING_MODE = booleanPreferencesKey("parking_mode")
        val MOTION_DETECTION = booleanPreferencesKey("motion_detection")
        val MOTION_SENSITIVITY = stringPreferencesKey("motion_sensitivity")

        val AUDIO_RECORDING = booleanPreferencesKey("audio_recording")
        val MICROPHONE_SENSITIVITY = stringPreferencesKey("microphone_sensitivity")
        val AUDIO_FORMAT = stringPreferencesKey("audio_format")

        val WIFI_AUTO_CONNECT = booleanPreferencesKey("wifi_auto_connect")
        val WIFI_SSID = stringPreferencesKey("wifi_ssid")
        val WIFI_PASSWORD = stringPreferencesKey("wifi_password")
        val CAMERA_IP = stringPreferencesKey("camera_ip")
        val HOTSPOT_MODE = booleanPreferencesKey("hotspot_mode")
        val HOTSPOT_SSID = stringPreferencesKey("hotspot_ssid")
        val HOTSPOT_PASSWORD = stringPreferencesKey("hotspot_password")

        val EXPOSURE_COMPENSATION = intPreferencesKey("exposure_compensation")
        val WHITE_BALANCE = stringPreferencesKey("white_balance")
        val CONTRAST = intPreferencesKey("contrast")
        val SATURATION = intPreferencesKey("saturation")
        val SHARPNESS = intPreferencesKey("sharpness")
        val HDR_ENABLED = booleanPreferencesKey("hdr_enabled")
        val NOISE_REDUCTION = booleanPreferencesKey("noise_reduction")

        val WATERMARK_ENABLED = booleanPreferencesKey("watermark_enabled")
        val WATERMARK_TEXT = stringPreferencesKey("watermark_text")
        val TIMESTAMP_OVERLAY = booleanPreferencesKey("timestamp_overlay")
        val SPEED_OVERLAY = booleanPreferencesKey("speed_overlay")
        val GPS_OVERLAY = booleanPreferencesKey("gps_overlay")
        val SHOW_LOGO = booleanPreferencesKey("show_logo")

        val NIGHT_MODE = booleanPreferencesKey("night_mode")
        val IMMERSIVE_MODE = booleanPreferencesKey("immersive_mode")
        val AUTO_HIDE_CONTROLS = booleanPreferencesKey("auto_hide_controls")
        val CONTROLS_TIMEOUT = intPreferencesKey("controls_timeout")
    }

    data class AppSettings(
        val videoResolution: String = "1080p",
        val videoQuality: String = "High",
        val frameRate: Int = 30,
        val bitrate: String = "8 Mbps",
        val codec: String = "H.264",

        val loopRecording: Boolean = true,
        val loopDuration: Int = 3,
        val autoDeleteOldest: Boolean = true,
        val preBufferRecording: Boolean = true,
        val preBufferSeconds: Int = 5,

        val gSensorSensitivity: String = "Medium",
        val impactDetection: Boolean = true,
        val parkingMode: Boolean = false,
        val motionDetection: Boolean = true,
        val motionSensitivity: String = "Medium",

        val audioRecording: Boolean = true,
        val microphoneSensitivity: String = "Medium",
        val audioFormat: String = "AAC",

        val wifiAutoConnect: Boolean = true,
        val wifiSSID: String = "OpenDash_Cam",
        val wifiPassword: String = "",
        val cameraIp: String = "",
        val hotspotMode: Boolean = false,
        val hotspotSSID: String = "OpenDash_Hotspot",
        val hotspotPassword: String = "opendash123",

        val exposureCompensation: Int = 0,
        val whiteBalance: String = "Auto",
        val contrast: Int = 50,
        val saturation: Int = 50,
        val sharpness: Int = 50,
        val hdrEnabled: Boolean = false,
        val noiseReduction: Boolean = true,

        val watermarkEnabled: Boolean = true,
        val watermarkText: String = "OpenDash",
        val timestampOverlay: Boolean = true,
        val speedOverlay: Boolean = true,
        val gpsOverlay: Boolean = false,
        val showLogo: Boolean = true,

        val nightMode: Boolean = false,
        val immersiveMode: Boolean = false,
        val autoHideControls: Boolean = true,
        val controlsTimeout: Int = 3000
    )

    val settingsFlow: Flow<AppSettings> = context.settingsDataStore.data.map { preferences ->
        AppSettings(
            videoResolution = preferences[VIDEO_RESOLUTION] ?: "1080p",
            videoQuality = preferences[VIDEO_QUALITY] ?: "High",
            frameRate = preferences[FRAME_RATE] ?: 30,
            bitrate = preferences[BITRATE] ?: "8 Mbps",
            codec = preferences[CODEC] ?: "H.264",

            loopRecording = preferences[LOOP_RECORDING] ?: true,
            loopDuration = preferences[LOOP_DURATION] ?: 3,
            autoDeleteOldest = preferences[AUTO_DELETE_OLDEST] ?: true,
            preBufferRecording = preferences[PRE_BUFFER_RECORDING] ?: true,
            preBufferSeconds = preferences[PRE_BUFFER_SECONDS] ?: 5,

            gSensorSensitivity = preferences[G_SENSOR_SENSITIVITY] ?: "Medium",
            impactDetection = preferences[IMPACT_DETECTION] ?: true,
            parkingMode = preferences[PARKING_MODE] ?: false,
            motionDetection = preferences[MOTION_DETECTION] ?: true,
            motionSensitivity = preferences[MOTION_SENSITIVITY] ?: "Medium",

            audioRecording = preferences[AUDIO_RECORDING] ?: true,
            microphoneSensitivity = preferences[MICROPHONE_SENSITIVITY] ?: "Medium",
            audioFormat = preferences[AUDIO_FORMAT] ?: "AAC",

            wifiAutoConnect = preferences[WIFI_AUTO_CONNECT] ?: true,
            wifiSSID = preferences[WIFI_SSID] ?: "OpenDash_Cam",
            wifiPassword = preferences[WIFI_PASSWORD] ?: "",
            cameraIp = preferences[CAMERA_IP] ?: "",
            hotspotMode = preferences[HOTSPOT_MODE] ?: false,
            hotspotSSID = preferences[HOTSPOT_SSID] ?: "OpenDash_Hotspot",
            hotspotPassword = preferences[HOTSPOT_PASSWORD] ?: "opendash123",

            exposureCompensation = preferences[EXPOSURE_COMPENSATION] ?: 0,
            whiteBalance = preferences[WHITE_BALANCE] ?: "Auto",
            contrast = preferences[CONTRAST] ?: 50,
            saturation = preferences[SATURATION] ?: 50,
            sharpness = preferences[SHARPNESS] ?: 50,
            hdrEnabled = preferences[HDR_ENABLED] ?: false,
            noiseReduction = preferences[NOISE_REDUCTION] ?: true,

            watermarkEnabled = preferences[WATERMARK_ENABLED] ?: true,
            watermarkText = preferences[WATERMARK_TEXT] ?: "OpenDash",
            timestampOverlay = preferences[TIMESTAMP_OVERLAY] ?: true,
            speedOverlay = preferences[SPEED_OVERLAY] ?: true,
            gpsOverlay = preferences[GPS_OVERLAY] ?: false,
            showLogo = preferences[SHOW_LOGO] ?: true,

            nightMode = preferences[NIGHT_MODE] ?: false,
            immersiveMode = preferences[IMMERSIVE_MODE] ?: false,
            autoHideControls = preferences[AUTO_HIDE_CONTROLS] ?: true,
            controlsTimeout = preferences[CONTROLS_TIMEOUT] ?: 3000
        )
    }

    suspend fun setNightMode(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[NIGHT_MODE] = enabled
        }
    }

    suspend fun setVideoResolution(resolution: String) {
        context.settingsDataStore.edit { preferences ->
            preferences[VIDEO_RESOLUTION] = resolution
        }
    }

    suspend fun setLoopRecording(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[LOOP_RECORDING] = enabled
        }
    }

    suspend fun setLoopDuration(minutes: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[LOOP_DURATION] = minutes
        }
    }

    suspend fun setAudioRecording(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[AUDIO_RECORDING] = enabled
        }
    }

    suspend fun setImpactDetection(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[IMPACT_DETECTION] = enabled
        }
    }

    suspend fun setMotionDetection(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[MOTION_DETECTION] = enabled
        }
    }

    suspend fun setCameraIp(ip: String) {
        context.settingsDataStore.edit { preferences ->
            preferences[CAMERA_IP] = ip
        }
    }

    suspend fun resetToDefaults() {
        context.settingsDataStore.edit { preferences ->
            preferences.clear()
        }
    }

    suspend fun updateSettings(newSettings: AppSettings) {
        context.settingsDataStore.edit { preferences ->
            preferences[VIDEO_RESOLUTION] = newSettings.videoResolution
            preferences[VIDEO_QUALITY] = newSettings.videoQuality
            preferences[FRAME_RATE] = newSettings.frameRate
            preferences[BITRATE] = newSettings.bitrate
            preferences[CODEC] = newSettings.codec

            preferences[LOOP_RECORDING] = newSettings.loopRecording
            preferences[LOOP_DURATION] = newSettings.loopDuration
            preferences[AUTO_DELETE_OLDEST] = newSettings.autoDeleteOldest
            preferences[PRE_BUFFER_RECORDING] = newSettings.preBufferRecording
            preferences[PRE_BUFFER_SECONDS] = newSettings.preBufferSeconds

            preferences[G_SENSOR_SENSITIVITY] = newSettings.gSensorSensitivity
            preferences[IMPACT_DETECTION] = newSettings.impactDetection
            preferences[PARKING_MODE] = newSettings.parkingMode
            preferences[MOTION_DETECTION] = newSettings.motionDetection
            preferences[MOTION_SENSITIVITY] = newSettings.motionSensitivity

            preferences[AUDIO_RECORDING] = newSettings.audioRecording
            preferences[MICROPHONE_SENSITIVITY] = newSettings.microphoneSensitivity
            preferences[AUDIO_FORMAT] = newSettings.audioFormat

            preferences[WIFI_AUTO_CONNECT] = newSettings.wifiAutoConnect
            preferences[WIFI_SSID] = newSettings.wifiSSID
            preferences[WIFI_PASSWORD] = newSettings.wifiPassword
            preferences[CAMERA_IP] = newSettings.cameraIp
            preferences[HOTSPOT_MODE] = newSettings.hotspotMode
            preferences[HOTSPOT_SSID] = newSettings.hotspotSSID
            preferences[HOTSPOT_PASSWORD] = newSettings.hotspotPassword

            preferences[EXPOSURE_COMPENSATION] = newSettings.exposureCompensation
            preferences[WHITE_BALANCE] = newSettings.whiteBalance
            preferences[CONTRAST] = newSettings.contrast
            preferences[SATURATION] = newSettings.saturation
            preferences[SHARPNESS] = newSettings.sharpness
            preferences[HDR_ENABLED] = newSettings.hdrEnabled
            preferences[NOISE_REDUCTION] = newSettings.noiseReduction

            preferences[WATERMARK_ENABLED] = newSettings.watermarkEnabled
            preferences[WATERMARK_TEXT] = newSettings.watermarkText
            preferences[TIMESTAMP_OVERLAY] = newSettings.timestampOverlay
            preferences[SPEED_OVERLAY] = newSettings.speedOverlay
            preferences[GPS_OVERLAY] = newSettings.gpsOverlay
            preferences[SHOW_LOGO] = newSettings.showLogo

            preferences[NIGHT_MODE] = newSettings.nightMode
            preferences[IMMERSIVE_MODE] = newSettings.immersiveMode
            preferences[AUTO_HIDE_CONTROLS] = newSettings.autoHideControls
            preferences[CONTROLS_TIMEOUT] = newSettings.controlsTimeout
        }
    }
}
