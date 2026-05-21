package com.kooduXA.opendash.data.protocol

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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.concurrent.TimeUnit

class NovatekHiHzProtocol : CameraProtocol {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val _connectionState = MutableStateFlow<CameraState>(CameraState.Disconnected)
    override val connectionState: StateFlow<CameraState> = _connectionState

    private var cameraIp: String = DEFAULT_IP
    private var heartbeatJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override suspend fun connect(ipAddress: String): Boolean = withContext(Dispatchers.IO) {
        _connectionState.value = CameraState.Connecting

        val candidates = buildIpCandidates(ipAddress)

        for (candidateIp in candidates) {
            cameraIp = candidateIp
            Log.d(TAG, "Trying camera IP: $cameraIp")

            try {
                if (!probeCamera()) {
                    Log.w(TAG, "Probe failed for IP: $cameraIp")
                    continue
                }

                val wakeupCommands = listOf(
                    "action=set&property=Net&value=connect",
                    "action=set&property=MovieLive&value=1",
                    "action=play&property=Live&value=1"
                )

                wakeupCommands.forEach { query ->
                    val response = sendCgiCommand(query)
                    Log.d(TAG, "Wake-up command '$query' -> '$response'")
                    delay(200)
                }

                val streamUrl = getLiveStreamUrl()
                Log.d(TAG, "Connected to $cameraIp, stream URL resolved to $streamUrl")

                _connectionState.value = CameraState.Connected
                startHeartbeat()
                return@withContext true
            } catch (e: Exception) {
                Log.e(TAG, "Connection attempt failed for IP: $cameraIp", e)
            }
        }

        _connectionState.value = CameraState.Error("Handshake Failed")
        return@withContext false
    }

    override suspend fun getLiveStreamUrl(): String = withContext(Dispatchers.IO) {
        val avResponse = sendCgiCommand("action=get&property=Camera.Preview.RTSP.av")
        Log.d(TAG, "RTSP AV response: '$avResponse'")

        val avValue = avResponse
            ?.substringAfter("av=", "")
            ?.trim()
            ?.toIntOrNull()

        val pathCandidates = when (avValue) {
            1 -> listOf("av1")
            2 -> listOf("v1", "av2")
            3 -> listOf("av2", "av3")
            4 -> listOf("av4", "av2", "av1")
            5 -> listOf("av5", "av4", "av1")
            6 -> listOf("stream0", "av4", "av1")
            null -> listOf("av4", "av2", "av1", "stream0")
            else -> listOf("av$avValue", "av4", "av2", "av1", "stream0")
        }

        val finalPath = pathCandidates.first()
        Log.d(TAG, "Using RTSP path: $finalPath")

        return@withContext "rtsp://$cameraIp/liveRTSP/$finalPath"
    }

    override suspend fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                try {
                    val code = sendCgiCommand(
                        query = "action=get&property=hbt&value=playback",
                        returnCode = true
                    )
                    if (code != "200") {
                        Log.w(TAG, "Heartbeat missed: $code")
                    } else {
                        Log.d(TAG, "Heartbeat OK")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat failed", e)
                }
                delay(3000)
            }
        }
    }

    override fun disconnect() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        _connectionState.value = CameraState.Disconnected
    }

    private fun sendCgiCommand(query: String, returnCode: Boolean = false): String? {
        val url = "http://$cameraIp/cgi-bin/Config.cgi?$query"
        return try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (returnCode) {
                    response.code.toString()
                } else {
                    if (response.isSuccessful) {
                        response.body?.string()?.trim()
                    } else {
                        Log.w(TAG, "HTTP ${response.code} for $url")
                        null
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "CGI request failed: $url", e)
            null
        }
    }

    private fun sendCommandAndGetResponse(commandCode: Int): Response {
        val url = "http://$cameraIp/cgi-bin/ipc?-function=exec&-command=$commandCode"
        val request = Request.Builder().url(url).build()
        return client.newCall(request).execute()
    }

    private fun buildIpCandidates(ipAddress: String): List<String> {
        return listOf(
            ipAddress.trim(),
            DEFAULT_IP,
            "192.168.1.1",
            "10.10.10.254"
        ).filter { it.isNotBlank() }.distinct()
    }

    private fun probeCamera(): Boolean {
        val probeQueries = listOf(
            "action=get&property=Camera.Preview.RTSP.av",
            "action=get&property=Model",
            "action=get&property=Net"
        )

        for (query in probeQueries) {
            val response = sendCgiCommand(query)
            if (!response.isNullOrBlank()) {
                Log.d(TAG, "Probe success with '$query' -> '$response'")
                return true
            }
        }
        return false
    }

    override suspend fun getFileList(): List<VideoFile> = withContext(Dispatchers.IO) {
        val xmlResponse = sendCgiCommand("action=dir&property=Normal&format=all&count=100&from=0")

        if (xmlResponse.isNullOrEmpty()) {
            Log.w(TAG, "Empty file list response")
            return@withContext emptyList()
        }

        return@withContext parseFileListXml(xmlResponse)
    }

    private fun parseFileListXml(xml: String): List<VideoFile> {
        val files = mutableListOf<VideoFile>()

        try {
            Log.d(TAG, "Parsing XML response: $xml")

            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var eventType = parser.eventType
            var rawName: String? = null
            var currentSize: String? = null
            var currentTime: String? = null

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagName = parser.name

                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (tagName) {
                            "name" -> rawName = parser.nextText()
                            "size" -> currentSize = parser.nextText()
                            "time" -> currentTime = parser.nextText()
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        if (tagName == "file" && rawName != null) {
                            val relativePath = when {
                                rawName.startsWith("/") -> rawName
                                else -> "/SD/Normal/$rawName"
                            }

                            val fullUrl = "http://$cameraIp$relativePath"

                            val directory = relativePath.substringBeforeLast("/", "") + "/"
                            val filename = relativePath.substringAfterLast("/")
                            val nameNoExt = filename.substringBeforeLast(".", filename)
                            val thumbFilename = ".$nameNoExt" + "_tb.jpg"
                            val thumbPath = directory + thumbFilename
                            val thumbUrl = "http://$cameraIp$thumbPath"

                            Log.d(
                                TAG,
                                "Found file: $filename | URL: $fullUrl | Thumb: $thumbUrl"
                            )

                            files.add(
                                VideoFile(
                                    filename = filename,
                                    downloadUrl = fullUrl,
                                    thumbnailUrl = thumbUrl,
                                    size = currentSize ?: "Unknown",
                                    time = currentTime ?: ""
                                )
                            )

                            rawName = null
                            currentSize = null
                            currentTime = null
                        }
                    }
                }

                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "XML parse error", e)
        }

        Log.d(TAG, "Total files parsed: ${files.size}")
        return files
    }

    override suspend fun deleteFile(filename: String): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Request to delete: $filename")

        var finalPath = filename

        if (finalPath.startsWith("http://") || finalPath.startsWith("https://")) {
            finalPath = finalPath.replace("http://$cameraIp", "")
            finalPath = finalPath.replace("https://$cameraIp", "")
        }

        if (!finalPath.startsWith("/")) {
            val storagePrefix = detectStoragePrefix()
            finalPath = "$storagePrefix$finalPath"
        }

        finalPath = finalPath.replace("/", "$")

        Log.d(TAG, "Sending delete command: property=$finalPath")

        val result = sendCgiCommand("action=del&property=$finalPath")
        val success = isSuccess(result)

        if (success) {
            Log.d(TAG, "Delete success")
        } else {
            Log.e(TAG, "Delete failed. Response: $result")
        }

        return@withContext success
    }

    private suspend fun detectStoragePrefix(): String = withContext(Dispatchers.IO) {
        val xmlResponse = sendCgiCommand("action=dir&property=Normal&format=all&count=1&from=0")

        return@withContext when {
            xmlResponse?.contains("/EMMC/Normal/F/") == true -> "/EMMC/Normal/F/"
            xmlResponse?.contains("/EMMC/Normal/") == true -> "/EMMC/Normal/"
            xmlResponse?.contains("/SD/Normal/") == true -> "/SD/Normal/"
            xmlResponse?.contains("/Card/Normal/") == true -> "/Card/Normal/"
            else -> "/SD/Normal/"
        }
    }

    override suspend fun formatSdCard(): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = sendCommandAndGetResponse(3015)
            if (response.isSuccessful && response.body != null) {
                val xml = response.body!!.string()
                Log.d(TAG, "Format response: $xml")
                return@withContext xml.contains("<Status>0</Status>")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Format SD failed", e)
        }
        return@withContext false
    }

    suspend fun setAudioRecording(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val value = if (enabled) "On" else "Off"
        val result = sendCgiCommand("action=set&property=SoundRecord&value=$value")
        Log.d(TAG, "Set audio recording '$value' -> '$result'")
        return@withContext isSuccess(result)
    }

    suspend fun getDeviceStatus(): DeviceStatus = withContext(Dispatchers.IO) {
        var isRecording = false
        var hasSdCard = true

        try {
            val recResponse = sendCgiCommand("action=get&property=Camera.Preview.MJPEG.status.record")
            if (recResponse?.contains("Recording", ignoreCase = true) == true) {
                isRecording = true
            }

            val sdResponse = sendCgiCommand("action=get&property=Camera.Menu.SD0")
            if (
                sdResponse?.contains("Insert", ignoreCase = true) == true ||
                sdResponse?.contains("Error", ignoreCase = true) == true
            ) {
                hasSdCard = false
            }

            Log.d(TAG, "Status poll -> rec: $isRecording, sd: $hasSdCard")
        } catch (e: Exception) {
            Log.e(TAG, "Status poll failed", e)
        }

        return@withContext DeviceStatus(
            isRecording = isRecording,
            hasSdCard = hasSdCard
        )
    }

    override suspend fun getStorageInfo(): StorageInfo? = withContext(Dispatchers.IO) {
        val response = sendCgiCommand("action=get&property=SDCard.Capacity")
        if (response.isNullOrBlank()) {
            return@withContext null
        }

        try {
            val numericLines = response
                .lines()
                .map { it.trim() }
                .filter { it.toLongOrNull() != null }
                .map { it.toLong() }

            if (numericLines.size >= 2) {
                val totalMb = numericLines[numericLines.size - 2]
                val freeMb = numericLines[numericLines.size - 1]

                return@withContext StorageInfo(
                    total = totalMb * 1024 * 1024,
                    free = freeMb * 1024 * 1024
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse storage info", e)
        }

        return@withContext null
    }

    override suspend fun startRecording(): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Sending rec toggle (start)...")
        val result = sendCgiCommand("action=set&property=Video&value=record")
        Log.d(TAG, "Rec toggle response: '$result'")
        return@withContext isSuccess(result)
    }

    override suspend fun stopRecording(): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Sending rec toggle (stop)...")
        val result = sendCgiCommand("action=set&property=Video&value=record")
        Log.d(TAG, "Rec toggle response: '$result'")
        return@withContext isSuccess(result)
    }

    override suspend fun takePhoto(): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Taking photo...")
        val result = sendCgiCommand("action=set&property=Camera.Capture&value=1")
        Log.d(TAG, "Photo response: '$result'")
        return@withContext isSuccess(result)
    }

    private fun isSuccess(response: String?): Boolean {
        if (response == null) return false
        val clean = response.trim()
        return clean.contains("OK", ignoreCase = true) || clean.startsWith("0")
    }

    suspend fun setWifiCredentials(ssid: String, password: String): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting WiFi update sequence...")

        val ssidCmd = "action=set&property=Net.WIFI_AP.SSID&value=$ssid"
        val ssidResult = sendCgiCommand(ssidCmd)
        if (!isSuccess(ssidResult)) {
            Log.e(TAG, "Failed to set SSID")
            return@withContext false
        }

        val passCmd = "action=set&property=Net.WIFI_AP.CryptoKey&value=$password"
        val passResult = sendCgiCommand(passCmd)
        if (!isSuccess(passResult)) {
            Log.e(TAG, "Failed to set password")
            return@withContext false
        }

        val resetCmd = "action=set&property=Net.Dev.1.Type&value=AP&property=Net&value=reset"
        val resetResult = sendCgiCommand(resetCmd)

        Log.d(TAG, "WiFi update complete. Reset result: $resetResult")
        return@withContext isSuccess(resetResult)
    }

    companion object {
        private const val TAG = "Novatek"
        private const val DEFAULT_IP = "192.168.0.1"
    }
}

data class DeviceStatus(
    val isRecording: Boolean,
    val hasSdCard: Boolean
)
