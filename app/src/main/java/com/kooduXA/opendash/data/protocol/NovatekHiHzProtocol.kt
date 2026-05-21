package com.kooduXA.opendash.data.protocol

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.util.Log
import com.kooduXA.opendash.domain.model.CameraState
import com.kooduXA.opendash.domain.model.StorageInfo
import com.kooduXA.opendash.domain.model.VideoFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class NovatekHiHzProtocol(
    private val context: Context
) : CameraProtocol {

    private var cameraIp: String = "192.168.0.1"
    private var baseCgiUrl: String = "http://192.168.0.1/cgi-bin/Config.cgi"
    private var liveRtspUrl: String? = null

    private val _connectionState = MutableStateFlow<CameraState>(CameraState.Disconnected)
    override val connectionState: StateFlow<CameraState> = _connectionState.asStateFlow()

    private val protocolScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    private val cgiPaths = listOf(
        "/cgi-bin/Config.cgi",
        "/cgi-bin/config.cgi",
        "/app/HttpAPI.asp"
    )

    private val probeQueries = listOf(
        "action=get&property=Camera.Menu.VideoRes",
        "action=get&property=Camera.Preview.RTSP.av",
        "action=get&property=Camera.System.Power",
        "action=dir&property=Normal&format=all&count=1&from=0"
    )

    override suspend fun connect(ipAddress: String): Boolean = withContext(Dispatchers.IO) {
        heartbeatJob?.cancel()
        liveRtspUrl = null

        val manualIp = ipAddress.trim()
        val ipCandidates = buildIpCandidates(manualIp)

        _connectionState.value = if (manualIp.isBlank()) {
            CameraState.Scanning
        } else {
            CameraState.Connecting
        }

        Log.d(TAG, "Starting discovery. Candidates: $ipCandidates")

        for (candidateIp in ipCandidates) {
            _connectionState.value = CameraState.Connecting
            Log.d(TAG, "Trying camera IP: $candidateIp")

            val discoveredBase = discoverWorkingBaseUrl(candidateIp)
            if (discoveredBase != null) {
                cameraIp = candidateIp
                baseCgiUrl = discoveredBase

                Log.d(TAG, "Handshake success. cameraIp=$cameraIp, baseCgiUrl=$baseCgiUrl")

                liveRtspUrl = discoverRtspUrl()
                Log.d(TAG, "Discovered RTSP URL: $liveRtspUrl")

                _connectionState.value = CameraState.Connected
                startHeartbeat()
                return@withContext true
            }
        }

        Log.w(TAG, "No compatible camera endpoint found")
        _connectionState.value = CameraState.Error("Camera discovery failed")
        false
    }

    override suspend fun getLiveStreamUrl(): String = withContext(Dispatchers.IO) {
        if (liveRtspUrl.isNullOrBlank()) {
            liveRtspUrl = discoverRtspUrl()
        }

        liveRtspUrl ?: "rtsp://$cameraIp/liveRTSP/av4"
    }

    override suspend fun startHeartbeat() = withContext(Dispatchers.IO) {
        heartbeatJob?.cancel()

        heartbeatJob = protocolScope.launch {
            while (isActive) {
                try {
                    val response = sendCgiCommand("action=get&property=Camera.System.Power")
                        ?: sendCgiCommand("action=get&property=Camera.Menu.VideoRes")

                    if (response.isNullOrBlank()) {
                        Log.w(TAG, "Heartbeat failed for $cameraIp")
                        _connectionState.value = CameraState.Error("Heartbeat failed")
                        break
                    } else {
                        if (_connectionState.value !is CameraState.Connected) {
                            _connectionState.value = CameraState.Connected
                        }
                        Log.d(TAG, "Heartbeat OK: $response")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat exception", e)
                    _connectionState.value = CameraState.Error(
                        e.message ?: "Heartbeat failed"
                    )
                    break
                }

                delay(3_000)
            }
        }
    }

    override suspend fun startRecording(): Boolean = withContext(Dispatchers.IO) {
        val result = sendCgiCommand("action=set&property=Camera.Record.Start&value=1")
            ?: sendCgiCommand("action=command&property=VideoRecord&value=start")

        Log.d(TAG, "Start recording result: $result")
        isSuccess(result)
    }

    override suspend fun stopRecording(): Boolean = withContext(Dispatchers.IO) {
        val result = sendCgiCommand("action=set&property=Camera.Record.Stop&value=1")
            ?: sendCgiCommand("action=command&property=VideoRecord&value=stop")

        Log.d(TAG, "Stop recording result: $result")
        isSuccess(result)
    }

    override suspend fun takePhoto(): Boolean = withContext(Dispatchers.IO) {
        val result = sendCgiCommand("action=set&property=Camera.Capture.Start&value=1")
            ?: sendCgiCommand("action=command&property=StillCapture&value=1")

        Log.d(TAG, "Take photo result: $result")
        isSuccess(result)
    }

    override fun disconnect() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        liveRtspUrl = null
        _connectionState.value = CameraState.Disconnected
        Log.d(TAG, "Disconnect called for $cameraIp")
    }

    override suspend fun getFileList(): List<VideoFile> = withContext(Dispatchers.IO) {
        val response = sendCgiCommand("action=dir&property=Normal&format=all&count=999&from=0")
            ?: return@withContext emptyList()

        Log.d(TAG, "File list raw response: $response")
        parseFileList(response)
    }

    override suspend fun deleteFile(filename: String): Boolean = withContext(Dispatchers.IO) {
        var finalPath = filename.trim()

        if (finalPath.startsWith("http://") || finalPath.startsWith("https://")) {
            finalPath = finalPath.substringAfter(cameraIp, finalPath)
        }

        if (!finalPath.startsWith("/")) {
            finalPath = detectStoragePrefix() + finalPath
        }

        finalPath = finalPath.replace("/", "$")
        Log.d(TAG, "Delete path transformed to: $finalPath")

        val result = sendCgiCommand("action=del&property=$finalPath")
        isSuccess(result)
    }

    override suspend fun getStorageInfo(): StorageInfo? = withContext(Dispatchers.IO) {
        val response = sendCgiCommand("action=get&property=Camera.Menu.StorageInfo")
            ?: sendCgiCommand("action=get&property=Camera.System.Misc.SDCard")
            ?: return@withContext null

        Log.d(TAG, "Storage info response: $response")

        val totalMb = Regex("""total(?:Size)?=(\d+)""", RegexOption.IGNORE_CASE)
            .find(response)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()

        val freeMb = Regex("""free(?:Size)?=(\d+)""", RegexOption.IGNORE_CASE)
            .find(response)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()

        val totalBytes = totalMb?.times(1024L * 1024L) ?: return@withContext null
        val freeBytes = freeMb?.times(1024L * 1024L) ?: return@withContext null

        StorageInfo(
            totalBytes = totalBytes,
            freeBytes = freeBytes
        )
    }

    override suspend fun formatSdCard(): Boolean = withContext(Dispatchers.IO) {
        val result = sendCgiCommand("action=set&property=Camera.Menu.Format&value=1")
            ?: sendCgiCommand("action=format")

        Log.d(TAG, "Format SD result: $result")
        isSuccess(result)
    }

    suspend fun setAudioRecording(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val value = if (enabled) "1" else "0"
        val result = sendCgiCommand("action=set&property=Camera.Menu.AudioRec&value=$value")
        Log.d(TAG, "Set audio recording result: $result")
        isSuccess(result)
    }

    suspend fun getDeviceStatus(): DeviceStatus = withContext(Dispatchers.IO) {
        val recordingResponse = sendCgiCommand("action=get&property=Camera.Record.Status")
            ?: sendCgiCommand("action=get&property=Camera.Menu.Record")

        val sdResponse = sendCgiCommand("action=get&property=Camera.System.Misc.SDCard")
            ?: sendCgiCommand("action=get&property=Camera.Menu.StorageInfo")

        val isRecording = recordingResponse?.contains("record", ignoreCase = true) == true ||
            recordingResponse?.contains("Recording", ignoreCase = true) == true ||
            recordingResponse?.contains("value=1", ignoreCase = true) == true

        val hasSdCard = sdResponse?.contains("sd", ignoreCase = true) == true ||
            sdResponse?.contains("card", ignoreCase = true) == true ||
            sdResponse?.contains("insert", ignoreCase = true) == true ||
            sdResponse?.contains("mounted", ignoreCase = true) == true

        DeviceStatus(
            isRecording = isRecording,
            hasSdCard = hasSdCard
        )
    }

    suspend fun setWifiCredentials(ssid: String, pass: String): Boolean = withContext(Dispatchers.IO) {
        val encodedSsid = URLEncoder.encode(ssid, "UTF-8")
        val encodedPass = URLEncoder.encode(pass, "UTF-8")

        val ssidResult = sendCgiCommand(
            "action=set&property=Camera.Menu.WIFI.SSID&value=$encodedSsid"
        )

        val passResult = sendCgiCommand(
            "action=set&property=Camera.Menu.WIFI.Password&value=$encodedPass"
        )

        Log.d(TAG, "Set Wi-Fi SSID result: $ssidResult")
        Log.d(TAG, "Set Wi-Fi password result: $passResult")

        isSuccess(ssidResult) && isSuccess(passResult)
    }

    private suspend fun discoverWorkingBaseUrl(ip: String): String? {
        for (cgiPath in cgiPaths) {
            val candidateBase = "http://$ip$cgiPath"

            for (probe in probeQueries) {
                val response = sendCgiCommandToBase(candidateBase, probe)
                if (!response.isNullOrBlank()) {
                    Log.d(TAG, "Probe success: base=$candidateBase, probe=$probe, response=$response")
                    return candidateBase
                }
            }
        }

        return null
    }

    private suspend fun discoverRtspUrl(): String? = withContext(Dispatchers.IO) {
        val avResponse = sendCgiCommand("action=get&property=Camera.Preview.RTSP.av")
        Log.d(TAG, "RTSP AV response: $avResponse")

        val avValue = avResponse
            ?.substringAfter("av=", "")
            ?.substringBefore("\n")
            ?.trim()
            ?.toIntOrNull()

        val pathCandidates = buildList {
            when (avValue) {
                1 -> add("liveRTSP/av1")
                2 -> add("liveRTSP/v1")
                3 -> add("liveRTSP/av2")
                4 -> add("liveRTSP/av4")
                5 -> add("liveRTSP/av5")
                else -> {
                    add("liveRTSP/av4")
                    add("liveRTSP/av2")
                    add("liveRTSP/av1")
                    add("liveRTSP/v1")
                }
            }

            add("liveRTSP/live")
            add("liveRTSP/stream0")
            add("live")
            add("stream0")
            add("livestream/12")
        }.distinct()

        for (path in pathCandidates) {
            val url = "rtsp://$cameraIp/$path"
            if (isRtspReachable(cameraIp, 554, path)) {
                Log.d(TAG, "RTSP verified: $url")
                return@withContext url
            } else {
                Log.d(TAG, "RTSP failed: $url")
            }
        }

        null
    }

    private fun sendCgiCommand(query: String, returnCode: Boolean = false): String? {
        return sendCgiCommandToBase(baseCgiUrl, query, returnCode)
    }

    private fun sendCgiCommandToBase(
        baseUrl: String,
        query: String,
        returnCode: Boolean = false
    ): String? {
        val url = "$baseUrl?$query"

        return try {
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (returnCode) return response.code.toString()

                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} for $url")
                    return null
                }

                val body = response.body?.string()?.trim()
                Log.d(TAG, "CGI OK [$query] -> $body")
                body
            }
        } catch (e: Exception) {
            Log.e(TAG, "CGI request failed: $url", e)
            null
        }
    }

    private fun buildIpCandidates(manualIp: String): List<String> {
        val result = linkedSetOf<String>()

        if (manualIp.isNotBlank()) {
            result += manualIp
        }

        getWifiGatewayIp()?.let { result += it }

        result += listOf(
            "192.168.0.1",
            "192.168.1.1",
            "192.168.42.1",
            "192.168.10.1"
        )

        return result.toList()
    }

    private fun getWifiGatewayIp(): String? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork ?: return null
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return null

            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return null
            }

            val linkProperties: LinkProperties = cm.getLinkProperties(activeNetwork) ?: return null
            val route = linkProperties.routes.firstOrNull {
                it.isDefaultRoute && it.gateway is Inet4Address
            }

            route?.gateway?.hostAddress
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read Wi-Fi gateway", e)
            null
        }
    }

    private fun isRtspReachable(host: String, port: Int, path: String): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 1500)
                socket.soTimeout = 1500

                val writer = socket.getOutputStream().bufferedWriter()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                writer.write(
                    "DESCRIBE rtsp://$host/$path RTSP/1.0\r\n" +
                        "CSeq: 1\r\n" +
                        "User-Agent: Opendash\r\n" +
                        "\r\n"
                )
                writer.flush()

                val firstLine = reader.readLine()
                Log.d(TAG, "RTSP probe first line for $path: $firstLine")

                firstLine?.contains("RTSP/1.0 200", ignoreCase = true) == true ||
                    firstLine?.contains("RTSP/1.0 401", ignoreCase = true) == true ||
                    firstLine?.contains("RTSP/1.0 454", ignoreCase = true) == true
            }
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun detectStoragePrefix(): String {
        val xmlResponse = sendCgiCommand("action=dir&property=Normal&format=all&count=1&from=0")
        return when {
            xmlResponse?.contains("/EMMC/", ignoreCase = true) == true -> "/EMMC/Normal/F/"
            xmlResponse?.contains("/SD/", ignoreCase = true) == true -> "/SD/Normal/"
            xmlResponse?.contains("/Card/", ignoreCase = true) == true -> "/Card/Normal/"
            else -> "/SD/Normal/"
        }
    }

    private fun isSuccess(result: String?): Boolean {
        if (result.isNullOrBlank()) return false

        return result.contains("ok", ignoreCase = true) ||
            result.contains("success", ignoreCase = true) ||
            result.contains("200") ||
            !result.contains("error", ignoreCase = true)
    }

    private fun parseFileList(raw: String): List<VideoFile> {
        val lines = raw.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val fileRegex = Regex("""([A-Za-z0-9_\-]+\.(mp4|mov|ts|avi))""", RegexOption.IGNORE_CASE)
        val sizeRegex = Regex("""size=(\d+)""", RegexOption.IGNORE_CASE)
        val timeRegex = Regex("""time=([^\n]+)""", RegexOption.IGNORE_CASE)
        val pathRegex = Regex("""(/[^,\s]+?\.(mp4|mov|ts|avi))""", RegexOption.IGNORE_CASE)

        val results = mutableListOf<VideoFile>()

        for (line in lines) {
            val fileName = fileRegex.find(line)?.groupValues?.getOrNull(1)
                ?: pathRegex.find(line)?.value?.substringAfterLast("/")

            if (fileName == null) continue

            val fullPath = pathRegex.find(line)?.value
            val url = if (fullPath != null) {
                "http://$cameraIp$fullPath"
            } else {
                "http://$cameraIp/DCIM/$fileName"
            }

            val sizeBytes = sizeRegex.find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()
            val dateText = timeRegex.find(line)?.groupValues?.getOrNull(1)?.trim().orEmpty()

            results += VideoFile(
                filename = fileName,
                downloadUrl = url,
                thumbnailUrl = url,
                size = sizeBytes?.let { formatBytes(it) } ?: "",
                time = if (dateText.isNotBlank()) dateText else "Unknown date"
            )
        }

        return results.distinctBy { it.filename }
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
        private const val TAG = "Novatek"
    }
}
