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
import okhttp3.HttpUrl
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
    private var appBasePath: List<String> = listOf("app")

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

    private val candidateBasePaths = listOf(
        listOf("app"),
        listOf("app", "ctrl"),
        emptyList()
    )

    private val probeRequests = listOf(
        ProbeRequest("getdeviceattr"),
        ProbeRequest("getparamitems", mapOf("param" to "all")),
        ProbeRequest("getparamvalue", mapOf("param" to "rec")),
        ProbeRequest("getsdinfo"),
        ProbeRequest("getmediainfo"),
        ProbeRequest("enterrecorder")
    )

    override suspend fun canHandle(ipAddress: String): Boolean = withContext(Dispatchers.IO) {
        val ip = ipAddress.trim()
        val discovered = discoverWorkingBasePath(ip)

        Log.d(TAG, "canHandle ip=$ip -> basePath=${discovered?.joinToString("/")}")

        discovered != null
    }

    override suspend fun connect(ipAddress: String): Boolean = withContext(Dispatchers.IO) {
        heartbeatJob?.cancel()

        cameraIp = ipAddress.trim()
        _connectionState.value = CameraState.Connecting

        val discoveredBasePath = discoverWorkingBasePath(cameraIp)
        if (discoveredBasePath == null) {
            _connectionState.value = CameraState.Error("APP API handshake failed on $cameraIp")
            return@withContext false
        }

        appBasePath = discoveredBasePath

        val attr = sendAppCommand("getdeviceattr")
        val items = sendAppCommand("getparamitems", mapOf("param" to "all"))
        val rec = sendAppCommand("getparamvalue", mapOf("param" to "rec"))
        val sd = sendAppCommand("getsdinfo")
        val media = sendAppCommand("getmediainfo")
        val enterRecorder = sendAppCommand("enterrecorder")
        val timeSync = syncTime()

        Log.d(TAG, "Connected APP basePath=${appBasePath.joinToString("/")}")
        Log.d(TAG, "getdeviceattr=$attr")
        Log.d(TAG, "getparamitems=$items")
        Log.d(TAG, "getparamvalue(rec)=$rec")
        Log.d(TAG, "getsdinfo=$sd")
        Log.d(TAG, "getmediainfo=$media")
        Log.d(TAG, "enterrecorder=$enterRecorder")
        Log.d(TAG, "setsystime=$timeSync")

        val success = listOf(attr, items, rec, sd, media, enterRecorder).any { !it.isNullOrBlank() }
        if (!success) {
            _connectionState.value = CameraState.Error("APP API handshake failed on $cameraIp")
            return@withContext false
        }

        _connectionState.value = CameraState.Connected
        startHeartbeat()
        true
    }

    override suspend fun getLiveStreamUrl(): String = withContext(Dispatchers.IO) {
        listOf(
            "rtsp://$cameraIp/liveRTSP/av4",
            "rtsp://$cameraIp/liveRTSP/av2",
            "rtsp://$cameraIp/liveRTSP/av1",
            "rtsp://$cameraIp/live",
            "rtsp://$cameraIp/stream0",
            "http://$cameraIp/live",
            "http://$cameraIp:8080/?action=stream"
        ).first()
    }

    override suspend fun startHeartbeat() = withContext(Dispatchers.IO) {
        heartbeatJob?.cancel()

        heartbeatJob = protocolScope.launch {
            while (isActive) {
                try {
                    val response = sendAppCommand("getdeviceattr")
                        ?: sendAppCommand("getparamvalue", mapOf("param" to "rec"))
                        ?: sendAppCommand("getsdinfo")

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
        val result = sendAppCommand("setparamvalue", mapOf("param" to "rec", "value" to "1"))
            ?: sendAppCommand("startrecord")
            ?: sendAppCommand("enterrecorder")

        isSuccess(result)
    }

    override suspend fun stopRecording(): Boolean = withContext(Dispatchers.IO) {
        val result = sendAppCommand("setparamvalue", mapOf("param" to "rec", "value" to "0"))
            ?: sendAppCommand("stoprecord")

        isSuccess(result)
    }

    override suspend fun takePhoto(): Boolean = withContext(Dispatchers.IO) {
        val result = sendAppCommand("capture")
            ?: sendAppCommand("takephoto")

        isSuccess(result)
    }

    override fun disconnect() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        _connectionState.value = CameraState.Disconnected
    }

    override suspend fun getFileList(): List<VideoFile> = withContext(Dispatchers.IO) {
        val mediaInfo = sendAppCommand("getmediainfo") ?: return@withContext emptyList()
        parseMediaInfo(mediaInfo)
    }

    override suspend fun deleteFile(filename: String): Boolean = withContext(Dispatchers.IO) {
        val cleanName = filename.substringAfterLast("/")
        val result = sendAppCommand("deletefile", mapOf("name" to cleanName))
            ?: sendAppCommand("delmedia", mapOf("name" to cleanName))

        isSuccess(result)
    }

    override suspend fun getStorageInfo(): StorageInfo? = withContext(Dispatchers.IO) {
        val response = sendAppCommand("getsdinfo") ?: return@withContext null

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
        val result = sendAppCommand("formatsd")
            ?: sendAppCommand("format")

        isSuccess(result)
    }

    suspend fun getDeviceStatus(): DeviceStatus = withContext(Dispatchers.IO) {
        val rec = sendAppCommand("getparamvalue", mapOf("param" to "rec"))
        val sd = sendAppCommand("getsdinfo")

        DeviceStatus(
            isRecording = rec?.contains("1") == true || rec?.contains("on", true) == true,
            hasSdCard = sd?.contains("sd", true) == true ||
                sd?.contains("card", true) == true ||
                sd?.contains("mount", true) == true
        )
    }

    suspend fun setWifiCredentials(ssid: String, pass: String): Boolean = withContext(Dispatchers.IO) {
        val result = sendAppCommand("setwifi", mapOf("ssid" to ssid, "pwd" to pass))
            ?: sendAppCommand("setapinfo", mapOf("ssid" to ssid, "pwd" to pass))

        isSuccess(result)
    }

    private suspend fun discoverWorkingBasePath(ip: String): List<String>? = withContext(Dispatchers.IO) {
        for (basePath in candidateBasePaths) {
            for (probe in probeRequests) {
                val response = simpleGet(buildUrl(ip = ip, basePath = basePath, path = probe.path, query = probe.query))
                Log.d(
                    TAG,
                    "APP probe ip=$ip base=${basePath.joinToString("/")} path=${probe.path} -> ${response?.take(160)}"
                )
                if (!response.isNullOrBlank()) {
                    return@withContext basePath
                }
            }
        }
        null
    }

    private fun sendAppCommand(
        path: String,
        query: Map<String, String> = emptyMap()
    ): String? {
        return simpleGet(buildUrl(ip = cameraIp, basePath = appBasePath, path = path, query = query))
    }

    private fun buildUrl(
        ip: String,
        basePath: List<String>,
        path: String,
        query: Map<String, String> = emptyMap()
    ): HttpUrl {
        val builder = HttpUrl.Builder()
            .scheme("http")
            .host(ip)

        basePath.forEach { segment ->
            if (segment.isNotBlank()) {
                builder.addPathSegment(segment)
            }
        }

        path.split("/")
            .filter { it.isNotBlank() }
            .forEach { segment ->
                builder.addPathSegment(segment)
            }

        query.forEach { (key, value) ->
            builder.addQueryParameter(key, value)
        }

        return builder.build()
    }

    private fun simpleGet(url: HttpUrl): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string()?.trim()
                Log.d(TAG, "GET $url -> code=${response.code}, body=${body?.take(200)}")

                if (!response.isSuccessful) return null
                body
            }
        } catch (e: Exception) {
            Log.d(TAG, "GET failed: $url", e)
            null
        }
    }

    private fun syncTime(): String? {
        val value = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
        return sendAppCommand("setsystime", mapOf("date" to value))
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

    private data class ProbeRequest(
        val path: String,
        val query: Map<String, String> = emptyMap()
    )

    companion object {
        private const val TAG = "AppHttpProtocol"
    }
}
