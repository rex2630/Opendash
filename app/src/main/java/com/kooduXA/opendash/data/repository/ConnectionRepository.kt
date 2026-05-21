package com.kooduXA.opendash.data.repository

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import com.kooduXA.opendash.data.protocol.CameraProtocol
import com.kooduXA.opendash.data.protocol.DeviceStatus
import com.kooduXA.opendash.data.protocol.NovatekHiHzProtocol
import com.kooduXA.opendash.domain.model.CameraState
import com.kooduXA.opendash.domain.model.VideoFile
import com.kooduXA.opendash.domain.model.WifiInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class ConnectionRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val _connectionState = MutableStateFlow<CameraState>(CameraState.Disconnected)
    val connectionState: StateFlow<CameraState> = _connectionState.asStateFlow()

    private val _activeProtocol = MutableStateFlow<CameraProtocol?>(null)
    val activeProtocol: StateFlow<CameraProtocol?> = _activeProtocol.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val downloadClient = OkHttpClient.Builder().build()

    @RequiresApi(Build.VERSION_CODES.R)
    fun startDiscovery() {
        scope.launch {
            try {
                _connectionState.value = CameraState.Scanning
                Log.d(TAG, "Starting camera discovery")

                val wifiInfo = getGatewayInfo()
                if (wifiInfo == null) {
                    Log.e(TAG, "No Wi-Fi gateway info found")
                    _connectionState.value = CameraState.Error("No Wi-Fi connection found")
                    return@launch
                }

                Log.d(TAG, "Active Wi-Fi gateway: ${wifiInfo.gatewayIp}")

                val candidateIps = buildCandidateIps(wifiInfo.gatewayIp)
                Log.d(TAG, "Candidate camera IPs: $candidateIps")

                var connectedProtocol: CameraProtocol? = null

                for (ip in candidateIps) {
                    coroutineContext.ensureActive()

                    Log.d(TAG, "Trying candidate IP: $ip")
                    _connectionState.value = CameraState.Connecting

                    val protocol = NovatekHiHzProtocol()

                    val success = try {
                        protocol.connect(ip)
                    } catch (e: Exception) {
                        Log.e(TAG, "Protocol connect failed for $ip", e)
                        false
                    }

                    if (success) {
                        connectedProtocol = protocol
                        Log.d(TAG, "Camera handshake success on $ip")
                        break
                    } else {
                        Log.w(TAG, "Camera handshake failed on $ip")
                    }
                }

                if (connectedProtocol != null) {
                    _activeProtocol.value = connectedProtocol
                    _connectionState.value = CameraState.Connected
                    Log.d(TAG, "Discovery complete: camera connected")
                } else {
                    _activeProtocol.value = null
                    _connectionState.value = CameraState.Error("Camera handshake failed on all candidate IPs")
                    Log.e(TAG, "Discovery failed on all candidate IPs")
                }
            } catch (e: Exception) {
                Log.e(TAG, "startDiscovery failed", e)
                _activeProtocol.value = null
                _connectionState.value = CameraState.Error("Discovery failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    fun disconnect() {
        try {
            _activeProtocol.value?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect failed", e)
        } finally {
            _activeProtocol.value = null
            _connectionState.value = CameraState.Disconnected
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun getGatewayInfo(): WifiInfo? {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val activeNetwork: Network = connectivityManager.activeNetwork ?: run {
            Log.w(TAG, "No active network")
            return null
        }

        val caps: NetworkCapabilities =
            connectivityManager.getNetworkCapabilities(activeNetwork) ?: run {
                Log.w(TAG, "No network capabilities for active network")
                return null
            }

        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            Log.w(TAG, "Active network is not Wi-Fi")
            return null
        }

        val linkProperties: LinkProperties =
            connectivityManager.getLinkProperties(activeNetwork) ?: run {
                Log.w(TAG, "No link properties for active Wi-Fi network")
                return null
            }

        val gateway = linkProperties.routes
            .firstOrNull { it.isDefaultRoute }
            ?.gateway
            ?.hostAddress
            ?: linkProperties.dhcpServerAddress?.hostAddress

        Log.d(TAG, "LinkProperties gateway resolved to: $gateway")

        return if (!gateway.isNullOrBlank()) {
            WifiInfo(
                ssid = "Unknown",
                gatewayIp = gateway
            )
        } else {
            Log.w(TAG, "Gateway IP could not be resolved")
            null
        }
    }

    private fun buildCandidateIps(gatewayIp: String): List<String> {
        val fallbacks = listOf(
            gatewayIp,
            "192.168.0.1",
            "192.168.1.1",
            "10.10.10.254"
        )

        return fallbacks
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    suspend fun getRecordings(): List<VideoFile> {
        return try {
            _activeProtocol.value?.getFileList() ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "getRecordings failed", e)
            emptyList()
        }
    }

    suspend fun downloadFile(
        url: String,
        filename: String,
        onProgress: (Float) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Starting direct download for: $filename from $url")

            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/OpenDash"
                )
            }

            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IOException("Failed to create MediaStore entry")

            try {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    val request = Request.Builder().url(url).build()

                    downloadClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IOException("HTTP ${response.code} while downloading $filename")
                        }

                        val body = response.body
                            ?: throw IOException("Empty response body while downloading $filename")

                        val totalBytes = body.contentLength()
                        var bytesCopied = 0L
                        val buffer = ByteArray(8 * 1024)

                        body.byteStream().use { inputStream ->
                            while (true) {
                                coroutineContext.ensureActive()

                                val bytes = inputStream.read(buffer)
                                if (bytes < 0) break

                                outputStream.write(buffer, 0, bytes)
                                bytesCopied += bytes

                                if (totalBytes > 0) {
                                    val progress = bytesCopied.toFloat() / totalBytes.toFloat()
                                    onProgress(progress.coerceIn(0f, 1f))
                                }
                            }
                        }

                        outputStream.flush()
                    }
                } ?: throw IOException("Failed to open MediaStore output stream")
            } catch (e: Exception) {
                Log.e(TAG, "Download failed, cleaning MediaStore entry", e)
                resolver.delete(uri, null, null)
                throw e
            }

            Log.d(TAG, "Download success: saved to $uri")
        }
    }

    suspend fun getStorageInfo() = try {
        activeProtocol.value?.getStorageInfo()
    } catch (e: Exception) {
        Log.e(TAG, "getStorageInfo failed", e)
        null
    }

    suspend fun formatSdCard(): Boolean {
        return try {
            activeProtocol.value?.formatSdCard() ?: false
        } catch (e: Exception) {
            Log.e(TAG, "formatSdCard failed", e)
            false
        }
    }

    suspend fun setAudioRecording(enabled: Boolean): Boolean {
        return try {
            (activeProtocol.value as? NovatekHiHzProtocol)?.setAudioRecording(enabled) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "setAudioRecording failed", e)
            false
        }
    }

    suspend fun getDeviceStatus(): DeviceStatus {
        return try {
            (activeProtocol.value as? NovatekHiHzProtocol)?.getDeviceStatus()
                ?: DeviceStatus(isRecording = false, hasSdCard = false)
        } catch (e: Exception) {
            Log.e(TAG, "getDeviceStatus failed", e)
            DeviceStatus(isRecording = false, hasSdCard = false)
        }
    }

    suspend fun updateWifi(ssid: String, pass: String): Boolean {
        return try {
            (activeProtocol.value as? NovatekHiHzProtocol)?.setWifiCredentials(ssid, pass) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "updateWifi failed", e)
            false
        }
    }

    companion object {
        private const val TAG = "ConnectionRepository"
    }
}
