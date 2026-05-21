package com.kooduXA.opendash.ui.screens

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kooduXA.opendash.data.protocol.DeviceStatus
import com.kooduXA.opendash.data.repository.ConnectionRepository
import com.kooduXA.opendash.domain.model.CameraState
import com.kooduXA.opendash.domain.model.VideoFile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val connectionRepository: ConnectionRepository
) : ViewModel() {

    val connectionState: StateFlow<CameraState> = connectionRepository.connectionState

    private val _files = MutableStateFlow<List<VideoFile>>(emptyList())
    val files = _files.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    private val _deviceStatus = MutableStateFlow(DeviceStatus(isRecording = false, hasSdCard = false))
    val deviceStatus = _deviceStatus.asStateFlow()

    private val _debugMessage = MutableStateFlow<String?>(null)
    val debugMessage = _debugMessage.asStateFlow()

    private var pollingJob: Job? = null

    init {
        observeConnectionState()
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            connectionRepository.connectionState.collect { state ->
                when (state) {
                    is CameraState.Connected -> {
                        _debugMessage.value = "Camera connected"
                        refreshAll()
                        startPolling()
                    }

                    is CameraState.Connecting -> {
                        _debugMessage.value = "Connecting to camera..."
                        stopPolling()
                    }

                    is CameraState.Scanning -> {
                        _debugMessage.value = "Scanning for camera..."
                        stopPolling()
                    }

                    is CameraState.Disconnected -> {
                        _debugMessage.value = "Camera disconnected"
                        stopPolling()
                        _files.value = emptyList()
                        _deviceStatus.value = DeviceStatus(
                            isRecording = false,
                            hasSdCard = false
                        )
                    }

                    is CameraState.Error -> {
                        _debugMessage.value = state.message
                        stopPolling()
                        _files.value = emptyList()
                        _deviceStatus.value = DeviceStatus(
                            isRecording = false,
                            hasSdCard = false
                        )
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun connect() {
        _debugMessage.value = "Starting discovery..."
        connectionRepository.startDiscovery()
    }

    fun disconnect() {
        stopPolling()
        connectionRepository.disconnect()
    }

    fun refreshAll() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                refreshFiles()
                refreshDeviceStatus()
            } catch (e: Exception) {
                _debugMessage.value = "Refresh failed: ${e.message ?: "unknown error"}"
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun refreshFiles() {
        viewModelScope.launch {
            try {
                _files.value = connectionRepository.getRecordings()
            } catch (e: Exception) {
                _debugMessage.value = "Failed to load recordings: ${e.message ?: "unknown error"}"
            }
        }
    }

    fun refreshDeviceStatus() {
        viewModelScope.launch {
            try {
                _deviceStatus.value = connectionRepository.getDeviceStatus()
            } catch (e: Exception) {
                _debugMessage.value = "Failed to load device status: ${e.message ?: "unknown error"}"
            }
        }
    }

    fun setAudioRecording(enabled: Boolean) {
        viewModelScope.launch {
            val success = connectionRepository.setAudioRecording(enabled)
            if (success) {
                _debugMessage.value = if (enabled) {
                    "Audio recording enabled"
                } else {
                    "Audio recording disabled"
                }
                refreshDeviceStatus()
            } else {
                _debugMessage.value = "Failed to update audio recording"
            }
        }
    }

    fun downloadFile(
        url: String,
        filename: String,
        onProgress: (Float) -> Unit = {}
    ) {
        viewModelScope.launch {
            try {
                _debugMessage.value = "Downloading $filename..."
                connectionRepository.downloadFile(url, filename, onProgress)
                _debugMessage.value = "Download completed: $filename"
            } catch (e: Exception) {
                _debugMessage.value = "Download failed: ${e.message ?: "unknown error"}"
            }
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                try {
                    refreshFiles()
                    refreshDeviceStatus()
                } catch (e: Exception) {
                    _debugMessage.value = "Polling failed: ${e.message ?: "unknown error"}"
                }
                delay(POLLING_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }

    companion object {
        private const val POLLING_INTERVAL_MS = 5_000L
    }
}
