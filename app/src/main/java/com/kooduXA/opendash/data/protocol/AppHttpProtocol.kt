package com.kooduXA.opendash.data.protocol

import android.content.Context
import com.kooduXA.opendash.data.debug.AppLogger
import com.kooduXA.opendash.domain.model.CameraEndpoint
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

    private var currentEndpoint: CameraEndpoint? = null
    private var appBasePath: List<String> = listOf("app")

    private val _connectionState = MutableStateFlow<CameraState>(CameraState.Disconnected)
    override val connectionState: StateFlow<CameraState> = _connectionState.asStateFlow()

    private val protocolScope = CoroutineScope(Job() + Dispatchers.IO)
    private var heartbeatJob: Job? = null

    private val fastClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    private val slowClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private val candidateBasePaths = listOf(
        listOf("app"),
        listOf("app", "ctrl"),
        emptyList()
    )

    // Dokumentace potvrzuje: getdeviceattr a getsdinfo jsou spolehlivé probe endpointy
    private val probeRequests = listOf(
        ProbeRequest("getdeviceattr"),
        ProbeRequest("getsdinfo")
    )

    override suspend fun canHandle(endpoint: CameraEndpoint): Boolean = withContext(Dispatchers.IO) {
        val ip = endpoint.ip.trim()
        val discovered = discoverWorkingBasePath(ip)
        AppLogger.d(TAG, "canHandle ip=$ip -> basePath=${discovered?.joinToString("/")}")
        discovered != null
    }

    override suspend fun connect(endpoint: CameraEndpoint): Boolean = withContext(Dispatchers.IO) {
        heartbeatJob?.cancel()

        val ip = endpoint.ip.trim()
        currentEndpoint = endpoint.copy(ip = ip)
        _connectionState.value = CameraState.Connecting
        AppLogger.i(TAG, "Connecting to camera at $ip")

        val discoveredBasePath = discoverWorkingBasePath(ip)
        if (discoveredBasePath == null) {
            AppLogger.e(TAG, "APP API handshake failed on $ip: no working base path")
            _connectionState.value = CameraState.Error("APP API handshake failed on $ip")
            currentEndpoint = null
            return@withContext false
        }

        appBasePath = discoveredBasePath

        // Dokumentované endpointy: getdeviceattr, getproductinfo, getsdinfo,
        // getbatteryinfo, getrecduration, getparamvalue, setparamvalue, setsystime
        val attr      = sendAppCommand("getdeviceattr")
        val product   = sendAppCommand("getproductinfo")
        val sd        = sendAppCommand("getsdinfo")
        val battery   = sendAppCommand("getbatteryinfo")
        val items     = sendAppCommand("getparamitems", mapOf("param" to "all"))
        val valuesAll = sendAppCommand("getparamvalue", mapOf("param" to "all"))
        val rec       = sendAppCommand("getparamvalue", mapOf("param" to "rec"))
        val timeSync  = syncTime()
        val media     = sendAppCommand("getmediainfo", client = slowClient)

        AppLogger.d(TAG, "getdeviceattr=$attr")
        AppLogger.d(TAG, "getproductinfo=$product")
        AppLogger.d(TAG, "getsdinfo=$sd")
        AppLogger.d(TAG, "getbatteryinfo=$battery")
        AppLogger.d(TAG, "getparamitems=$items")
        AppLogger.d(TAG, "getparamvalue(all)=$valuesAll")
        AppLogger.d(TAG, "getparamvalue(rec)=$rec")
        AppLogger.d(TAG, "getmediainfo=$media")
        AppLogger.d(TAG, "setsystime=$timeSync")
        AppLogger.d(TAG, "Connected APP basePath=${appBasePath.joinToString("/")}")

        val handshakeOk = listOf(attr, product, sd, battery, items, valuesAll, rec, media)
            .any { looksLikeUsableResponse(it) }

        if (!handshakeOk) {
            AppLogger.e(TAG, "APP API handshake returned no usable data for $ip")
            _connectionState.value = CameraState.Error("APP API handshake failed on $ip")
            currentEndpoint = null
            return@withContext false
        }

        _connectionState.value = CameraState.Connected
        AppLogger.i(TAG, "Camera connected via ${appBasePath.joinToString("/")}")
        startHeartbeat()
        true
    }

    override suspend fun getLiveStreamUrl(): String = withContext(Dispatchers.IO) {
        val ip = requireCurrentIp()
        // Dokumentace potvrzuje RTSP stream na /liveRTSP; ostatní jsou fallbacky
        listOf(
            "rtsp://$ip/liveRTSP/av4",
            "rtsp://$ip/liveRTSP/av2",
            "rtsp://$ip/liveRTSP/av1",
            "rtsp://$ip/live",
            "rtsp://$ip/stream0"
        ).first()
    }

    override suspend fun startHeartbeat() = withContext(Dispatchers.IO) {
        heartbeatJob?.cancel()

        heartbeatJob = protocolScope.launch {
            AppLogger.d(TAG, "Heartbeat started")

            while (isActive) {
                try {
                    val response = sendAppCommand("getdeviceattr")
                        ?: sendAppCommand("getparamvalue", mapOf("param" to "rec"))
                        ?: sendAppCommand("getsdinfo")

                    if (response.isNullOrBlank()) {
                        AppLogger.e(TAG, "Heartbeat failed: empty response")
                        _connectionState.value = CameraState.Error("Heartbeat failed")
                        break
                    } else if (_connectionState.value !is CameraState.Connected) {
                        AppLogger.i(TAG, "Heartbeat restored connection state")
                        _connectionState.value = CameraState.Connected
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Heartbeat exception", e)
                    _connectionState.value = CameraState.Error(e.message ?: "Heartbeat failed")
                    break
                }

                delay(3_000)
            }

            AppLogger.d(TAG, "Heartbeat stopped")
        }
    }

    // Dokumentace: nahrávání se ovládá přes setparamvalue?param=rec&value=1
    override suspend fun startRecording(): Boolean = withContext(Dispatchers.IO) {
        val result = sendAppCommand("setparamvalue", mapOf("param" to "rec", "value" to "1"))
        AppLogger.d(TAG, "startRecording result=$result")
        isSuccess(result)
    }

    override suspend fun stopRecording(): Boolean = withContext(Dispatchers.IO) {
        val result = sendAppCommand("setparamvalue", mapOf("param" to "rec", "value" to "0"))
        AppLogger.d(TAG, "stopRecording result=$result")
        isSuccess(result)
    }

    // Dokumentace neobsahuje explicitní takephoto endpoint — setparamvalue je správná cesta
    override suspend fun takePhoto(): Boolean = withContext(Dispatchers.IO) {
        val result = sendAppCommand("setparamvalue", mapOf("param" to "cap", "value" to "1"))
            ?: sendAppCommand("capture")
        AppLogger.d(TAG, "takePhoto result=$result")
        isSuccess(result)
    }

    override fun disconnect() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        currentEndpoint = null
        _connectionState.value = CameraState.Disconnected
        AppLogger.i(TAG, "Camera disconnected")
    }

    // Dokumentace: playback session = playback?param=enter → getfilelist → playback?param=exit
    // getthumbnail?filename=/mnt/sdcard/... pro náhledy
    override suspend fun getFileList(): List<VideoFile> = withContext(Dispatchers.IO) {
        val files = mutableListOf<VideoFile>()

        val playbackEnter = sendAppCommand("playback", mapOf("param" to "enter"), client = slowClient)
        AppLogger.d(TAG, "getFileList enterPlayback=$playbackEnter")

        val fileListResponse = sendAppCommand("getfilelist", client = slowClient)
        if (!fileListResponse.isNullOrBlank()) {
            files += parseFileList(fileListResponse)
        }

        if (files.isEmpty()) {
            val mediaInfo = sendAppCommand("getmediainfo", client = slowClient)
            if (!mediaInfo.isNullOrBlank()) {
                files += parseMediaInfo(mediaInfo)
            }
        }

        val playbackExit = sendAppCommand("playback", mapOf("param" to "exit"), client = slowClient)
        AppLogger.d(TAG, "getFileList exitPlayback=$playbackExit")
        AppLogger.d(TAG, "getFileList parsed ${files.size} files")

        files.distinctBy { it.filename }
    }

    override suspend fun deleteFile(filename: String): Boolean = withContext(Dispatchers.IO) {
        val cleanName = filename.substringAfterLast("/")
        val result = sendAppCommand("deletefile", mapOf("name" to cleanName))
            ?: sendAppCommand("delmedia", mapOf("name" to cleanName))
        AppLogger.d(TAG, "deleteFile name=$cleanName result=$result")
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

        if (totalBytes == null || freeBytes == null) {
            AppLogger.w(TAG, "getStorageInfo parse failed for response=$response")
            return@withContext null
        }

        val info = StorageInfo(totalBytes = totalBytes, freeBytes = freeBytes)
        AppLogger.d(TAG, "getStorageInfo total=$totalBytes free=$freeBytes")
        info
    }

    override suspend fun formatSdCard(): Boolean = withContext(Dispatchers.IO) {
        val result = sendAppCommand("formatsd")
            ?: sendAppCommand("format")
        AppLogger.d(TAG, "formatSdCard result=$result")
        isSuccess(result)
    }

    // Dokumentace: getparamvalue?param=rec + getsdinfo pro status
    suspend fun getDeviceStatus(): DeviceStatus = withContext(Dispatchers.IO) {
        val rec = sendAppCommand("getparamvalue", mapOf("param" to "rec"))
        val sd  = sendAppCommand("getsdinfo")

        val status = DeviceStatus(
            isRecording = rec?.contains("1") == true || rec?.contains("on", true) == true,
            hasSdCard   = sd?.contains("sd", true) == true ||
                          sd?.contains("card", true) == true ||
                          sd?.contains("mount", true) == true
        )

        AppLogger.d(TAG, "getDeviceStatus rec=$rec sd=$sd status=$status")
        status
    }

    // Dokumentace: getproductinfo endpoint potvrzen v vii_http.py
    suspend fun getProductInfo(): String? = withContext(Dispatchers.IO) {
        val result = sendAppCommand("getproductinfo")
        AppLogger.d(TAG, "getProductInfo result=$result")
        result
    }

    // Dokumentace: getbatteryinfo endpoint potvrzen v vii_http.py
    suspend fun getBatteryInfo(): String? = withContext(Dispatchers.IO) {
        val result = sendAppCommand("getbatteryinfo")
        AppLogger.d(TAG, "getBatteryInfo result=$result")
        result
    }

    // Dokumentace: getrecduration endpoint potvrzen v vii_http.py
    suspend fun getRecDuration(): String? = withContext(Dispatchers.IO) {
        val result = sendAppCommand("getrecduration")
        AppLogger.d(TAG, "getRecDuration result=$result")
        result
    }

    // Dokumentace: setparamvalue je obecná setter funkce pro všechny parametry
    suspend fun setWifiCredentials(ssid: String, pass: String): Boolean = withContext(Dispatchers.IO) {
        val result = sendAppCommand("setparamvalue", mapOf("param" to "ssid", "value" to ssid))
        val result2 = sendAppCommand("setparamvalue", mapOf("param" to "pwd", "value" to pass))
        AppLogger.d(TAG, "setWifiCredentials ssid=$ssid result=$result result2=$result2")
        isSuccess(result) || isSuccess(result2)
    }

    private suspend fun discoverWorkingBasePath(ip: String): List<String>? = withContext(Dispatchers.IO) {
        for (basePath in candidateBasePaths) {
            for (probe in probeRequests) {
                val url = buildUrl(ip = ip, basePath = basePath, path = probe.path, query = probe.query)
                val response = simpleGet(url, fastClient)

                AppLogger.d(
                    TAG,
                    "probe ip=$ip base=${basePath.joinToString("/")} path=${probe.path} -> ${response?.take(80)}"
                )

                if (looksLikeUsableResponse(response)) {
                    return@withContext basePath
                }
            }
        }
        null
    }

    private fun sendAppCommand(
        path: String,
        query: Map<String, String> = emptyMap(),
        client: OkHttpClient = fastClient
    ): String? {
        val ip = requireCurrentIp()
        val url = buildUrl(ip = ip, basePath = appBasePath, path = path, query = query)
        return simpleGet(url, client)
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
            if (segment.isNotBlank()) builder.addPathSegment(segment)
        }

        path.split("/")
            .filter { it.isNotBlank() }
            .forEach { segment -> builder.addPathSegment(segment) }

        query.forEach { (key, value) ->
            builder.addQueryParameter(key, value)
        }

        return builder.build()
    }

    private fun simpleGet(url: HttpUrl, client: OkHttpClient): String? {
        return try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()?.trim()
                AppLogger.d(TAG, "GET $url -> code=${response.code}, body=${body?.take(200)}")
                if (!response.isSuccessful) return null
                body
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "GET failed: $url", e)
            null
        }
    }

    private fun syncTime(): String? {
        val value = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
        return sendAppCommand("setsystime", mapOf("date" to value), client = slowClient)
    }

    private fun looksLikeUsableResponse(result: String?): Boolean {
        if (result.isNullOrBlank()) return false
        if (result.contains("error", true)) return false
        return true
    }

    private fun isSuccess(result: String?): Boolean {
        if (result.isNullOrBlank()) return false
        return result.contains("ok", true) ||
            result.contains("success", true) ||
            result.contains("\"result\":0") ||
            result.contains("\"result\":\"0\"") ||
            result.contains("200") ||
            !result.contains("error", true)
    }

    // Dokumentace: getthumbnail?filename=/mnt/sdcard/soubor.mp4 (přesný název parametru z vii_http.py)
    private fun parseFileList(raw: String): List<VideoFile> {
        val ip = requireCurrentIp()
        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }

        val absolutePathRegex = Regex(
            """/mnt/sdcard/[A-Za-z0-9_/\-.]+""",
            RegexOption.IGNORE_CASE
        )
        val filenameRegex = Regex(
            """([A-Za-z0-9_\-]+\.(mp4|mov|ts|avi|jpg))""",
            RegexOption.IGNORE_CASE
        )
        val sizeRegex = Regex("""(?:size|filesize)[=:](\d+)""", RegexOption.IGNORE_CASE)
        val timeRegex = Regex("""(?:time|date)[=:]([^\n,]+)""", RegexOption.IGNORE_CASE)

        return lines.mapNotNull { line ->
            val absolutePath = absolutePathRegex.find(line)?.value
            val filename = absolutePath?.substringAfterLast("/")
                ?: filenameRegex.find(line)?.groupValues?.getOrNull(1)
                ?: return@mapNotNull null

            val sizeBytes = sizeRegex.find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()
            val time = timeRegex.find(line)?.groupValues?.getOrNull(1)?.trim().orEmpty()

            VideoFile(
                filename = filename,
                downloadUrl = absolutePath?.let { "http://$ip$it" }
                    ?: "http://$ip/mnt/sdcard/$filename",
                thumbnailUrl = absolutePath?.let { buildThumbnailUrl(ip, it) }
                    ?: "http://$ip/mnt/sdcard/$filename",
                size = sizeBytes?.let { formatBytes(it) } ?: "",
                time = if (time.isNotBlank()) time else "Unknown date"
            )
        }.distinctBy { it.filename }
    }

    private fun parseMediaInfo(raw: String): List<VideoFile> {
        val ip = requireCurrentIp()
        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
        val fileRegex = Regex("""([A-Za-z0-9_\-]+\.(mp4|mov|ts|avi|jpg))""", RegexOption.IGNORE_CASE)
        val absolutePathRegex = Regex("""/mnt/sdcard/[A-Za-z0-9_/\-.]+""", RegexOption.IGNORE_CASE)
        val sizeRegex = Regex("""size[=:](\d+)""", RegexOption.IGNORE_CASE)
        val timeRegex = Regex("""time[=:]([^\n,]+)""", RegexOption.IGNORE_CASE)

        return lines.mapNotNull { line ->
            val absolutePath = absolutePathRegex.find(line)?.value
            val filename = absolutePath?.substringAfterLast("/")
                ?: fileRegex.find(line)?.groupValues?.getOrNull(1)
                ?: return@mapNotNull null

            val sizeBytes = sizeRegex.find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()
            val time = timeRegex.find(line)?.groupValues?.getOrNull(1)?.trim().orEmpty()

            VideoFile(
                filename = filename,
                downloadUrl = absolutePath?.let { "http://$ip$it" }
                    ?: "http://$ip/mnt/sdcard/$filename",
                thumbnailUrl = absolutePath?.let { buildThumbnailUrl(ip, it) }
                    ?: "http://$ip/mnt/sdcard/$filename",
                size = sizeBytes?.let { formatBytes(it) } ?: "",
                time = if (time.isNotBlank()) time else "Unknown date"
            )
        }.distinctBy { it.filename }
    }

    // Dokumentace z vii_http.py: getthumbnail používá parametr "filename", ne "file"
    private fun buildThumbnailUrl(ip: String, absolutePath: String): String {
        return buildUrl(
            ip = ip,
            basePath = appBasePath,
            path = "getthumbnail",
            query = mapOf("filename" to absolutePath)
        ).toString()
    }

    private fun requireCurrentIp(): String {
        return currentEndpoint?.ip?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("AppHttpProtocol is not connected to any endpoint")
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
