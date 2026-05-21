package com.kooduXA.opendash.data.protocol

import android.content.Context
import android.util.Log
import com.kooduXA.opendash.domain.model.CameraState
import com.kooduXA.opendash.domain.model.StorageInfo
import com.kooduXA.opendash.domain.model.VideoFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class AppHttpProtocol(
    private val context: Context
) : CameraProtocol {

    override val protocolName: String = "APP HTTP"

    private var cameraIp: String = "192.168.169.1"
    private var baseUrl: String = "http://192.168.169.1/app"

    private val _connectionState = MutableStateFlow<CameraState>(CameraState.Disconnected)
    override val connectionState: StateFlow<CameraState> = _connectionState.asStateFlow()

    private val protocolScope = CoroutineScope(Job() + Dispatchers.IO)
    private var heartbeatJob: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    override suspend fun canHandle(ipAddress: String): Boolean = withContext(Dispatchers.IO) {
        val ip = ipAddress.trim()
        val candidateBase = "http://$ip/app"

        val attr = simpleGet("$candidateBase/getdeviceattr")
        val items = simpleGet("$candidateBase/getparamitems?param=all")

        !attr.isNullOrBlank() || !items.isNullOrBlank()
    }

    override suspend fun connect(ipAddress: String): Boolean = withContext(Dispatchers.IO) {
        heartbeatJob?.cancel()

        cameraIp = ipAddress.trim()
        baseUrl = "http://$cameraIp/app"
        _connectionState.value = CameraState.Connecting

        val attr = simpleGet("$baseUrl/getdeviceattr")
        val items = simpleGet("$baseUrl/getparamitems?param=all")
        val enterRecorder = simpleGet("$baseUrl/enterrecorder")
        val timeSync = syncTime()

        Log.d(TAG, "getdeviceattr=$attr")
        Log.d(TAG, "getparamitems=$items")
        Log.d(TAG, "enterrecorder=$enterRecorder")
        Log.d(TAG, "setsystime=$timeSync")

        val success = !attr.isNullOrBlank() || !items.isNullOrBlank() || !enterRecorder.isNullOrBlank()
        if (!success) {
            _connectionState.value = CameraState.Error("APP API handshake failed on $cameraIp")
            return@withContext false
        }

        _connectionState.value = CameraState.Connected
        startHeartbeat()
        true
    }

    override suspend fun getLiveStreamUrl(): String = withContext(Dispatchers.IO) {
        val candidates = listOf(
            "rtsp://$cameraIp/liveRTSP/av4",
            "rtsp://$cameraIp/liveRTSP/av2",
            "rtsp://$cameraIp/liveRTSP/av1",
            "rtsp://$cameraIp/live",
            "rtsp://$cameraIp/stream0",
            "http://$cameraIp/live",
            "http://$cameraIp:8080/?action=stream"
        )

        candidates.first()
    }

    override suspend fun startHeartbeat() = withContext(Dispatchers.IO) {
        heartbeatJob?.cancel()

        heartbeatJob = protocolScope.launch {
            while (isActive) {
                try {
                    val response = simpleGet("$baseUrl/getdeviceattr")
                        ?: simpleGet("$baseUrl/getparamvalue?param=rec")

                    if (response.isNullOrBlank()) {
                        _connectionState.value = CameraState.Error("Heartbeat failed")
                        break
                    } else if (_connectionState.value !is CameraState.Connected) {
                        _connectionState.value = CameraState.Connected
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat exception", e)
                    _connectionState.value = CameraState.Error(e.message ?: "Heartbeat failed")
                    break
                }

                delay(3_000)
            }
        }
    }

    override suspend fun startRecording(): Boolean = withContext(Dispatchers.IO) {
        val result = simpleGet("$baseUrl/setparamvalue?param=rec&value=1")
            ?: simpleGet("$baseUrl/startrecord")
            ?: simpleGet("$baseUrl/enterrecorder")
        isSuccess(result)
    }

    override suspend fun stopRecording(): Boolean = withContext(Dispatchers.IO) {
        val result = simpleGet("$baseUrl/setparamvalue?param=rec&value=0")
            ?: simpleGet("$baseUrl/stoprecord")
        isSuccess(result)
    }

    override suspend fun takePhoto(): Boolean = withContext(Dispatchers.IO) {
        val result = simpleGet("$baseUrl/capture")
            ?: simpleGet("$baseUrl/takephoto")
        isSuccess(result)
    }

    override fun disconnect() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        _connectionState.value = CameraState.Disconnected
    }

    override suspend fun getFileList(): List<VideoFile> = withContext(Dispatchers.IO) {
        val mediaInfo = simpleGet("$baseUrl/getmediainfo") ?: return@withContext emptyList()
        parseMediaInfo(mediaInfo)
    }

    override suspend fun deleteFile(filename: String): Boolean = withContext(Dispatchers.IO) {
        val cleanName = filename.substringAfterLast("/")
        val result = simpleGet("$baseUrl/deletefile?name=$cleanName")
            ?: simpleGet("$baseUrl/delmedia?name=$cleanName")
        isSuccess(result)
    }

    override suspend fun getStorageInfo(): StorageInfo? = withContext(Dispatchers.IO) {
        val response = simpleGet("$baseUrl/getsdinfo") ?: return@withContext null

        val totalMb = Regex("""total(?:size)?[=:](\d+)""", RegexOption.IGNORE_CASE)
            .find(response)?.groupValues?.getOrNull(1)?.toLongOrNull()

        val freeMb = Regex("""free(?:size)?[=:](\d+)""", RegexOption.IGNORE_CASE)
            .find(response)?.groupValues?.getOrNull(1)?.toLongOrNull()

        val totalBytes = totalMb?.times(1024L * 1024L)
        val freeBytes = freeMb?.times(1024L * 1024L)

        if (totalBytes == null || freeBytes == null) return@withContext null
        StorageInfo(totalBytes = totalBytes, freeBytes = freeBytes)
    }

    override suspend fun formatSdCard(): Boolean = withContext(Dispatchers.IO) {
        val result = simpleGet("$baseUrl/formatsd")
            ?: simpleGet("$baseUrl/format")
        isSuccess(result)
    }

    suspend fun getDeviceStatus(): DeviceStatus = withContext(Dispatchers.IO) {
        val rec = simpleGet("$baseUrl/getparamvalue?param=rec")
        val sd = simpleGet("$baseUrl/getsdinfo")

        DeviceStatus(
            isRecording = rec?.contains("1") == true || rec?.contains("on", true) == true,
            hasSdCard = sd?.contains("sd", true) == true ||
                sd?.contains("card", true) == true ||
                sd?.contains("mount", true) == true
        )
    }

    suspend fun setWifiCredentials(ssid: String, pass: String): Boolean = withContext(Dispatchers.IO) {
        val result = simpleGet("$baseUrl/setwifi?ssid=$ssid&pwd=$pass")
            ?: simpleGet("$baseUrl/setapinfo?ssid=$ssid&pwd=$pass")
        isSuccess(result)
    }

    private fun simpleGet(url: String): String? {
        return try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()?.trim()
            }
        } catch (e: Exception) {
            Log.d(TAG, "GET failed: $url", e)
            null
        }
    }

    private fun syncTime(): String? {
        val value = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
        return simpleGet("$baseUrl/setsystime?date=$value")
    }

    private fun isSuccess(result: String?): Boolean {
        if (result.isNullOrBlank()) return false
        return result.contains("ok", true) ||
            result.contains("success", true) ||
            result.contains("200") ||
            !result.contains("error", true)
    }

    private fun parseMediaInfo(raw: String): List<VideoFile> {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
        val fileRegex = Regex("""([A-Za-z0-9_\-]+\.(mp4|mov|ts|avi|jpg))""", RegexOption.IGNORE_CASE)
        val sizeRegex = Regex("""size[=:](\d+)""", RegexOption.IGNORE_CASE)
        val timeRegex = Regex("""time[=:]([^\n,]+)""", RegexOption.IGNORE_CASE)

        return lines.mapNotNull { line ->
            val filename = fileRegex.find(line)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
            val sizeBytes = sizeRegex.find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()
            val time = timeRegex.find(line)?.groupValues?.getOrNull(1)?.trim().orEmpty()

            VideoFile(
                filename = filename,
                downloadUrl = "http://$cameraIp/DCIM/$filename",
                thumbnailUrl = "http://$cameraIp/DCIM/$filename",
                size = sizeBytes?.let { formatBytes(it) } ?: "",
                time = if (time.isNotBlank()) time else "Unknown date"
            )
        }.distinctBy { it.filename }
    }

    private fun formatBytes(bytes: Long): String {
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0

        return when {
            bytes >= gb -> String.format("%.2f GB", bytes / gb)
            bytes >= mb -> String.format("%.2f MB", bytes / mb)
            bytes >= kb -> String.format("%.2f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    companion object {
        private const val TAG = "AppHttpProtocol"
    }
}
