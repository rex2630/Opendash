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
import androidx.annotation.RequiresApi
import com.kooduXA.opendash.data.debug.DebugLogStore
import com.kooduXA.opendash.data.protocol.AppHttpProtocol
import com.kooduXA.opendash.data.protocol.CameraProtocol
import com.kooduXA.opendash.data.protocol.DeviceStatus
import com.kooduXA.opendash.data.protocol.NovatekCgiProtocol
import com.kooduXA.opendash.domain.model.CameraState
import com.kooduXA.opendash.domain.model.VideoFile
import com.kooduXA.opendash.domain.model.WifiInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
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
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    private val _connectionState = MutableStateFlow<CameraState>(CameraState.Disconnected)
    val connectionState: StateFlow<CameraState> = _connectionState.asStateFlow()

    private val _activeProtocol = MutableStateFlow<CameraProtocol?>(null)
    val activeProtocol: StateFlow<CameraProtocol?> = _activeProtocol.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadClient = OkHttpClient.Builder().build()

    private var protocolStateJob: Job? = null

    @RequiresApi(Build.VERSION_CODES.R)
    fun startDiscovery() {
        scope.launch {
            try {
                disconnectInternal(clearState = false)

                _connectionState.value = CameraState.Scanning
                DebugLogStore.i(TAG, "Starting camera discovery")

                val manualCameraIp = getManualCameraIp()
                val gatewayIp = getGatewayInfo()?.gatewayIp
                val candidateIps = buildCandidateIps(manualCameraIp, gatewayIp)

                DebugLogStore.d(TAG, "Manual IP: $manualCameraIp")
                DebugLogStore.d(TAG, "Gateway IP: $gatewayIp")
                DebugLogStore.d(TAG, "Candidate IPs: $candidateIps")

                val protocolFactories: List<() -> CameraProtocol> = listOf(
                    { AppHttpProtocol(context) },
                    { NovatekCgiProtocol(context) }
                )

                var connectedProtocol: CameraProtocol? = null

                outer@ for (ip in candidateIps) {
                    coroutineContext.ensureActive()
                    _connectionState.value = CameraState.Connecting
                    DebugLogStore.i(TAG, "Trying IP: $ip")

                    for (factory in protocolFactories) {
                        val protocol = factory()
                        DebugLogStore.d(TAG, "Probing ${protocol.protocolName} on $ip")

                        val canHandle = try {
                            protocol.canHandle(ip)
                        } catch (e: Exception) {
                            DebugLogStore.e(
                                TAG,
                                "Probe failed for ${protocol.protocolName} on $ip",
                                e
                            )
                            false
                        }

                        DebugLogStore.d(
                            TAG,
                            "Probe result ${protocol.protocolName} on $ip -> $canHandle"
                        )

                        if (!canHandle) continue

                        val success = try {
                            DebugLogStore.i(TAG, "Connecting ${protocol.protocolName} on $ip")
                            protocol.connect(ip)
                        } catch (e: Exception) {
                            DebugLogStore.e(
                                TAG,
                                "Connect failed for ${protocol.protocolName} on $ip",
                                e
                            )
                            false
                        }

                        DebugLogStore.d(
                            TAG,
                            "Connect result ${protocol.protocolName} on $ip -> $success"
                        )

                        if (success) {
                            connectedProtocol = protocol
                            DebugLogStore.i(
                                TAG,
                                "Connected using ${protocol.protocolName} on $ip"
                            )
                            break@outer
                        }
                    }
                }

                if (connectedProtocol != null) {
                    _activeProtocol.value = connectedProtocol
                    bindProtocolState(connectedProtocol)
                } else {
                    _activeProtocol.value = null
                    _connectionState.value = CameraState.Error("No supported camera found")
                    DebugLogStore.w(TAG, "Discovery finished: no supported camera found")
                }
            } catch (e: Exception) {
                DebugLogStore.e(TAG, "startDiscovery failed", e)
                _activeProtocol.value = null
                _connectionState.value =
                    CameraState.Error("Discovery failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    fun disconnect() {
        disconnectInternal(clearState = true)
    }

    private fun disconnectInternal(clearState: Boolean) {
        try {
            protocolStateJob?.cancel()
            protocolStateJob = null
            DebugLogStore.d(TAG, "Disconnecting active protocol")
            _activeProtocol.value?.disconnect()
        } catch (e: Exception) {
            DebugLogStore.e(TAG, "Disconnect failed", e)
        } finally {
            _activeProtocol.value = null
            if (clearState) {
                _connectionState.value = CameraState.Disconnected
            }
            DebugLogStore.d(TAG, "Disconnect completed clearState=$clearState")
        }
    }

    private fun bindProtocolState(protocol: CameraProtocol) {
        protocolStateJob?.cancel()
        protocolStateJob = scope.launch {
            protocol.connectionState.collectLatest { state ->
                DebugLogStore.d(TAG, "Protocol state from ${protocol.protocolName}: $state")
                _connectionState.value = state
            }
        }
    }

    private suspend fun getManualCameraIp(): String? {
        val ip = settingsRepository.settingsFlow.first().cameraIp.trim()
        val result = ip.takeIf { it.isNotEmpty() }
        DebugLogStore.d(TAG, "Resolved manual camera IP: $result")
        return result
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun getGatewayInfo(): WifiInfo? {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val activeNetwork: Network = connectivityManager.activeNetwork ?: run {
            DebugLogStore.w(TAG, "No active network")
            return null
        }

        val caps: NetworkCapabilities =
            connectivityManager.getNetworkCapabilities(activeNetwork) ?: run {
                DebugLogStore.w(TAG, "No network capabilities for active network")
                return null
            }

        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            DebugLogStore.d(TAG, "Active network is not Wi-Fi")
            return null
        }

        val linkProperties: LinkProperties =
            connectivityManager.getLinkProperties(activeNetwork) ?: run {
                DebugLogStore.w(TAG, "No LinkProperties for active network")
                return null
            }

        val gateway = linkProperties.routes
            .firstOrNull { it.isDefaultRoute && it.gateway?.hostAddress?.contains('.') == true }
            ?.gateway
            ?.hostAddress
            ?: linkProperties.dhcpServerAddress?.hostAddress

        DebugLogStore.d(TAG, "Resolved gateway from LinkProperties: $gateway")

        return gateway?.takeIf { it.isNotBlank() }?.let {
            WifiInfo(ssid = "Unknown", gatewayIp = it)
        }
    }

    private fun buildCandidateIps(
        manualIp: String?,
        gatewayIp: String?
    ): List<String> {
        val candidates = listOf(
            manualIp,
            gatewayIp,
            "192.168.169.1",
            "192.168.0.1",
            "192.168.1.1",
            "192.168.42.1",
            "192.168.10.1",
            "10.10.10.254"
        )
            .mapNotNull { it?.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        DebugLogStore.d(TAG, "Built candidate IP list: $candidates")
        return candidates
    }

    suspend fun getRecordings(): List<VideoFile> {
        return try {
            val recordings = _activeProtocol.value?.getFileList() ?: emptyList()
            DebugLogStore.d(TAG, "getRecordings -> ${recordings.size} files")
            recordings
        } catch (e: Exception) {
            DebugLogStore.e(TAG, "getRecordings failed", e)
            emptyList()
        }
    }

    suspend fun downloadFile(
        url: String,
        filename: String,
        onProgress: (Float) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            DebugLogStore.i(TAG, "Starting download filename=$filename url=$url")

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
                        DebugLogStore.d(
                            TAG,
                            "Download response code=${response.code} filename=$filename"
                        )

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
                        DebugLogStore.i(
                            TAG,
                            "Download completed filename=$filename bytesCopied=$bytesCopied"
                        )
                    }
                } ?: throw IOException("Failed to open MediaStore output stream")
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                DebugLogStore.e(TAG, "Download failed filename=$filename", e)
                throw e
            }
        }
    }

    suspend fun getStorageInfo() = try {
        val info = activeProtocol.value?.getStorageInfo()
        DebugLogStore.d(TAG, "getStorageInfo -> $info")
        info
    } catch (e: Exception) {
        DebugLogStore.e(TAG, "getStorageInfo failed", e)
        null
    }

    suspend fun formatSdCard(): Boolean {
        return try {
            val result = activeProtocol.value?.formatSdCard() ?: false
            DebugLogStore.d(TAG, "formatSdCard -> $result")
            result
        } catch (e: Exception) {
            DebugLogStore.e(TAG, "formatSdCard failed", e)
            false
        }
    }

    suspend fun setAudioRecording(enabled: Boolean): Boolean {
        return try {
            val result = when (val protocol = activeProtocol.value) {
                is NovatekCgiProtocol -> protocol.setAudioRecording(enabled)
                else -> false
            }
            DebugLogStore.d(TAG, "setAudioRecording enabled=$enabled -> $result")
            result
        } catch (e: Exception) {
            DebugLogStore.e(TAG, "setAudioRecording failed", e)
            false
        }
    }

    suspend fun getDeviceStatus(): DeviceStatus {
        return try {
            val status = when (val protocol = activeProtocol.value) {
                is NovatekCgiProtocol -> protocol.getDeviceStatus()
                is AppHttpProtocol -> protocol.getDeviceStatus()
                else -> DeviceStatus(isRecording = false, hasSdCard = false)
            }
            DebugLogStore.d(TAG, "getDeviceStatus -> $status")
            status
        } catch (e: Exception) {
            DebugLogStore.e(TAG, "getDeviceStatus failed", e)
            DeviceStatus(isRecording = false, hasSdCard = false)
        }
    }

    suspend fun updateWifi(ssid: String, pass: String): Boolean {
        return try {
            val result = when (val protocol = activeProtocol.value) {
                is NovatekCgiProtocol -> protocol.setWifiCredentials(ssid, pass)
                is AppHttpProtocol -> protocol.setWifiCredentials(ssid, pass)
                else -> false
            }
            DebugLogStore.d(TAG, "updateWifi ssid=$ssid -> $result")
            result
        } catch (e: Exception) {
            DebugLogStore.e(TAG, "updateWifi failed", e)
            false
        }
    }

    companion object {
        private const val TAG = "ConnectionRepository"
    }
}
