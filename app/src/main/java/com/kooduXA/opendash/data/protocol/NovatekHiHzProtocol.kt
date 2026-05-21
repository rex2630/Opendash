package com.kooduXA.opendash.data.protocol

import android.util.Log
import com.kooduXA.opendash.domain.model.StorageInfo
import com.kooduXA.opendash.domain.model.VideoFile
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class NovatekHiHzProtocol : CameraProtocol {

    private var cameraIp: String = "192.168.0.1"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    override suspend fun connect(ipAddress: String): Boolean {
        cameraIp = ipAddress.trim().ifBlank { "192.168.0.1" }
        Log.d(TAG, "Trying Novatek connection on $cameraIp")

        val probes = listOf(
            "action=get&property=Camera.Menu.VideoRes",
            "action=get&property=Camera.Preview.RTSP.av",
            "action=get&property=Camera.System.Power",
            "action=dir&property=Normal&format=all&count=1&from=0"
        )

        for (probe in probes) {
            val response = sendCgiCommand(probe)
            if (!response.isNullOrBlank()) {
                Log.d(TAG, "Handshake success on $cameraIp via probe: $probe -> $response")
                return true
            }
        }

        Log.w(TAG, "Handshake failed on $cameraIp")
        return false
    }

    override fun disconnect() {
        Log.d(TAG, "Disconnect called for $cameraIp")
    }

    override suspend fun getLiveStreamUrl(): String {
        val avResponse = sendCgiCommand("action=get&property=Camera.Preview.RTSP.av")
        Log.d(TAG, "RTSP AV response: $avResponse")

        val avValue = avResponse
            ?.substringAfter("av=", "")
            ?.substringBefore("\n")
            ?.trim()
            ?.toIntOrNull()

        val candidatePaths = buildList {
            when (avValue) {
                1 -> add("av1")
                2 -> add("v1")
                3 -> add("av2")
                4 -> add("av4")
                5 -> add("av5")
                else -> {
                    add("av4")
                    add("av2")
                    add("av1")
                    add("v1")
                }
            }
            add("live")
            add("stream0")
        }.distinct()

        val selectedPath = candidatePaths.first()
        Log.d(TAG, "Using RTSP path '$selectedPath' on $cameraIp")

        return "rtsp://$cameraIp/liveRTSP/$selectedPath"
    }

    override suspend fun getFileList(): List<VideoFile> {
        val response = sendCgiCommand("action=dir&property=Normal&format=all&count=999&from=0")
            ?: return emptyList()

        Log.d(TAG, "File list raw response: $response")

        return parseFileList(response)
    }

    override suspend fun deleteFile(filename: String): Boolean {
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
        return isSuccess(result)
    }

    override suspend fun getStorageInfo(): StorageInfo? {
        val response = sendCgiCommand("action=get&property=Camera.Menu.StorageInfo")
            ?: sendCgiCommand("action=get&property=Camera.System.Misc.SDCard")
            ?: return null

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

        val usedMb = if (totalMb != null && freeMb != null) totalMb - freeMb else null

        return StorageInfo(
            total = totalMb?.let { "${it}MB" } ?: "Unknown",
            used = usedMb?.let { "${it}MB" } ?: "Unknown",
            free = freeMb?.let { "${it}MB" } ?: "Unknown"
        )
    }

    override suspend fun formatSdCard(): Boolean {
        val result = sendCgiCommand("action=set&property=Camera.Menu.Format&value=1")
            ?: sendCgiCommand("action=format")
        Log.d(TAG, "Format SD result: $result")
        return isSuccess(result)
    }

    suspend fun setAudioRecording(enabled: Boolean): Boolean {
        val value = if (enabled) "1" else "0"
        val result = sendCgiCommand("action=set&property=Camera.Menu.AudioRec&value=$value")
        Log.d(TAG, "Set audio recording result: $result")
        return isSuccess(result)
    }

    suspend fun getDeviceStatus(): DeviceStatus {
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

        return DeviceStatus(
            isRecording = isRecording,
            hasSdCard = hasSdCard
        )
    }

    suspend fun setWifiCredentials(ssid: String, pass: String): Boolean {
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

        return isSuccess(ssidResult) && isSuccess(passResult)
    }

    private fun sendCgiCommand(query: String, returnCode: Boolean = false): String? {
        val url = "http://$cameraIp/cgi-bin/Config.cgi?$query"
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
            result.contains("value=0").not()
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
                name = fileName,
                url = url,
                date = if (dateText.isNotBlank()) dateText else "Unknown date",
                size = sizeBytes?.let { formatBytes(it) } ?: ""
            )
        }

        return results.distinctBy { it.name }
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
