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
import com.kooduXA.opendash.data.debug.AppLogger
import com.kooduXA.opendash.data.protocol.AppHttpProtocol
import com.kooduXA.opendash.data.protocol.CameraProtocol
import com.kooduXA.opendash.data.protocol.DeviceStatus
import com.kooduXA.opendash.data.protocol.NovatekCgiProtocol
import com.kooduXA.opendash.domain.model.CameraEndpoint
import com.kooduXA.opendash.domain.model.CameraState
import com.kooduXA.opendash.domain.model.ConnectionMode
import com.kooduXA.opendash.domain.model.EndpointSource
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

                val settings = settingsRepository.settingsFlow.first()
                val gatewayIp = getGatewayInfo()?.gatewayIp
                val candidateEndpoints = buildCandidateEndpoints(
                    manualCameraIp = settings.cameraIp,
                    connectionMode = settings.connectionMode,
                    preferManualFirst = settings.preferManualFirst,
                    gatewayIp = gatewayIp
                )

                when (settings.connectionMode) {
                    ConnectionMode.AUTO_DISCOVERY -> {
                        _connectionState.value = CameraState.Scanning
                        AppLogger.i(TAG, "Starting auto-discovery only")
                    }

                    ConnectionMode.MANUAL_IP -> {
                        _connectionState.value = CameraState.Connecting
                        AppLogger.i(TAG, "Starting manual IP connection only")
                    }

                    ConnectionMode.AUTO_WITH_MANUAL_FALLBACK -> {
                        _connectionState.value = CameraState.Scanning
                        AppLogger.i(TAG, "Starting hybrid discovery/manual connection flow")
                    }
                }

                AppLogger.d(TAG, "Connection mode: ${settings.connectionMode}")
                AppLogger.d(TAG, "Manual IP: ${settings.cameraIp}")
                AppLogger.d(TAG, "Prefer manual first: ${settings.preferManualFirst}")
                AppLogger.d(TAG, "Gateway IP: $gatewayIp")
                AppLogger.d(TAG, "Candidate endpoints: $candidateEndpoints")

                val protocolFactories: List<() -> CameraProtocol> = listOf(
                    { AppHttpProtocol(context) },
                    { NovatekCgiProtocol(context) }
                )

                var connectedProtocol: CameraProtocol? = null
                var connectedEndpoint: CameraEndpoint? = null

                outer@ for (endpoint in candidateEndpoints) {
                    coroutineContext.ensureActive()
                    _connectionState.value = CameraState.Connecting
                    AppLogger.i(TAG, "Trying endpoint ip=${endpoint.ip} source=${endpoint.source}")

                    for (factory in protocolFactories) {
                        val protocol = factory()
                        AppLogger.d(
                            TAG,
                            "Probing ${protocol.protocolName} on ip=${endpoint.ip} source=${endpoint.source}"
                        )

                        val canHandle = try {
                            protocol.canHandle(endpoint)
                        } catch (e: Exception) {
                            AppLogger.e(
                                TAG,
                                "Probe failed for ${protocol.protocolName} on ${endpoint.ip}",
                                e
                            )
                            false
                        }

                        AppLogger.d(
                            TAG,
                            "Probe result ${protocol.protocolName} on ${endpoint.ip} -> $canHandle"
                        )

                        if (!canHandle) continue

                        val success = try {
                            AppLogger.i(
                                TAG,
                                "Connecting ${protocol.protocolName} on ${endpoint.ip}"
                            )
                            protocol.connect(endpoint)
                        } catch (e: Exception) {
                            AppLogger.e(
                                TAG,
                                "Connect failed for ${protocol.protocolName} on ${endpoint.ip}",
                                e
                            )
                            false
                        }

                        AppLogger.d(
                            TAG,
                            "Connect result ${protocol.protocolName} on ${endpoint.ip} -> $success"
                        )

                        if (success) {
                            connectedProtocol = protocol
                            connectedEndpoint = endpoint
                            AppLogger.i(
                                TAG,
                                "Connected using ${protocol.protocolName} on ${endpoint.ip} source=${endpoint.source}"
                            )
                            break@outer
                        }
                    }
                }

                if (connectedProtocol != null) {
                    _activeProtocol.value = connectedProtocol
                    bindProtocolState(connectedProtocol)
                    AppLogger.i(TAG, "Active endpoint: $connectedEndpoint")
                } else {
                    _activeProtocol.value = null
                    _connectionState.value = buildConnectionErrorState(
                        settings.connectionMode,
                        settings.cameraIp
                    )
                    AppLogger.w(TAG, "Connection finished: no supported camera found")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "startDiscovery failed", e)
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
            AppLogger.d(TAG, "Disconnecting active protocol")
            _activeProtocol.value?.disconnect()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Disconnect failed", e)
        } finally {
            _activeProtocol.value = null
            if (clearState) {
                _connectionState.value = CameraState.Disconnected
            }
            AppLogger.d(TAG, "Disconnect completed clearState=$clearState")
        }
    }

    private fun bindProtocolState(protocol: CameraProtocol) {
        protocolStateJob?.cancel()
        protocolStateJob = scope.launch {
            protocol.connectionState.collectLatest { state ->
                AppLogger.d(TAG, "Protocol state from ${protocol.protocolName}: $state")
                _connectionState.value = state
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun getGatewayInfo(): WifiInfo? {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val activeNetwork: Network = connectivityManager.activeNetwork ?: run {
            AppLogger.w(TAG, "No active network")
            return null
        }

        val caps: NetworkCapabilities =
            connectivityManager.getNetworkCapabilities(activeNetwork) ?: run {
                AppLogger.w(TAG, "No network capabilities for active network")
                return null
            }

        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            AppLogger.d(TAG, "Active network is not Wi-Fi")
            return null
        }

        val linkProperties: LinkProperties =
            connectivityManager.getLinkProperties(activeNetwork) ?: run {
                AppLogger.w(TAG, "No LinkProperties for active network")
                return null
            }

        val gateway = linkProperties.routes
            .firstOrNull { it.isDefaultRoute && it.gateway?.hostAddress?.contains('.') == true }
            ?.gateway
            ?.hostAddress
            ?: linkProperties.dhcpServerAddress?.hostAddress

        AppLogger.d(TAG, "Resolved gateway from LinkProperties: $gateway")

        return gateway?.takeIf { it.isNotBlank() }?.let {
            WifiInfo(ssid = "Unknown", gatewayIp = it)
        }
    }

    private fun buildCandidateEndpoints(
        manualCameraIp: String,
        connectionMode: ConnectionMode,
        preferManualFirst: Boolean,
        gatewayIp: String?
    ): List<CameraEndpoint> {
        val manualEndpoint = manualCameraIp.trim()
            .takeIf { it.isNotEmpty() }
            ?.let {
                CameraEndpoint(
                    ip = it,
                    source = EndpointSource.MANUAL_SETTINGS,
                    label = "Manual IP"
                )
            }

        val autoEndpoints = buildAutoCandidateIps(gatewayIp).map { ip ->
            CameraEndpoint(
                ip = ip,
                source = EndpointSource.AUTO_DISCOVERY,
                label = "Auto discovery"
            )
        }

        val endpoints = when (connectionMode) {
            ConnectionMode.AUTO_DISCOVERY -> autoEndpoints
            ConnectionMode.MANUAL_IP -> listOfNotNull(manualEndpoint)
            ConnectionMode.AUTO_WITH_MANUAL_FALLBACK -> {
                if (preferManualFirst) {
                    listOfNotNull(manualEndpoint) + autoEndpoints
                } else {
                    autoEndpoints + listOfNotNull(manualEndpoint)
                }
            }
        }.distinctBy { it.ip }

        AppLogger.d(TAG, "Built candidate endpoints: $endpoints")
        return endpoints
    }

    private fun buildAutoCandidateIps(gatewayIp: String?): List<String> {
        val candidates = listOf(
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

        AppLogger.d(TAG, "Built auto candidate IP list: $candidates")
        return candidates
    }

    private fun buildConnectionErrorState(
        connectionMode: ConnectionMode,
        manualCameraIp: String
    ): CameraState.Error {
        val cleanManualIp = manualCameraIp.trim()

        return when (connectionMode) {
            ConnectionMode.MANUAL_IP -> {
                CameraState.Error(
                    "Could not connect to manual IP: ${cleanManualIp.ifBlank { "not set" }}"
                )
            }

            ConnectionMode.AUTO_WITH_MANUAL_FALLBACK -> {
                CameraState.Error(
                    if (cleanManualIp.isNotBlank()) {
                        "No supported camera found via auto-discovery or manual IP: $cleanManualIp"
                    } else {
                        "No supported camera found"
                    }
                )
            }

            ConnectionMode.AUTO_DISCOVERY -> CameraState.Error("No supported camera found")
        }
    }

    suspend fun getRecordings(): List<VideoFile> {
        return try {
            val recordings = _activeProtocol.value?.getFileList() ?: emptyList()
            AppLogger.d(TAG, "getRecordings -> ${recordings.size} files")
            recordings
        } catch (e: Exception) {
            AppLogger.e(TAG, "getRecordings failed", e)
            emptyList()
        }
    }

    suspend fun downloadFile(
        url: String,
        filename: String,
        onProgress: (Float) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            AppLogger.i(TAG, "Starting download filename=$filename url=$url")

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
                        AppLogger.d(
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
                        AppLogger.i(
                            TAG,
                            "Download completed filename=$filename bytesCopied=$bytesCopied"
                        )
                    }
                } ?: throw IOException("Failed to open MediaStore output stream")
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                AppLogger.e(TAG, "Download failed filename=$filename", e)
                throw e
            }
        }
    }

    suspend fun getStorageInfo() = try {
        val info = activeProtocol.value?.getStorageInfo()
        AppLogger.d(TAG, "getStorageInfo -> $info")
        info
    } catch (e: Exception) {
        AppLogger.e(TAG, "getStorageInfo failed", e)
        null
    }

    suspend fun formatSdCard(): Boolean {
        return try {
            val result = activeProtocol.value?.formatSdCard() ?: false
            AppLogger.d(TAG, "formatSdCard -> $result")
            result
        } catch (e: Exception) {
            AppLogger.e(TAG, "formatSdCard failed", e)
            false
        }
    }

    suspend fun setAudioRecording(enabled: Boolean): Boolean {
        return try {
            val result = when (val protocol = activeProtocol.value) {
                is NovatekCgiProtocol -> protocol.setAudioRecording(enabled)
                else -> false
            }
            AppLogger.d(TAG, "setAudioRecording enabled=$enabled -> $result")
            result
        } catch (e: Exception) {
            AppLogger.e(TAG, "setAudioRecording failed", e)
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
            AppLogger.d(TAG, "getDeviceStatus -> $status")
            status
        } catch (e: Exception) {
            AppLogger.e(TAG, "getDeviceStatus failed", e)
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
            AppLogger.d(TAG, "updateWifi ssid=$ssid -> $result")
            result
        } catch (e: Exception) {
            AppLogger.e(TAG, "updateWifi failed", e)
            false
        }
    }

    companion object {
        private const val TAG = "ConnectionRepository"
    }
}
