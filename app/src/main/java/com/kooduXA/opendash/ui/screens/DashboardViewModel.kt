package com.kooduXA.opendash.ui.screens

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kooduXA.opendash.data.repository.ConnectionRepository
import com.kooduXA.opendash.data.repository.SettingsRepository
import com.kooduXA.opendash.domain.model.CameraState
import com.kooduXA.opendash.domain.model.VideoFile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
@RequiresApi(Build.VERSION_CODES.R)
class DashboardViewModel @Inject constructor(
    private val connectionRepository: ConnectionRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private var lastCommandTime: Long = 0L
    private var recordingStartTimestamp: Long = 0L
    private var statusPollingJob: Job? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    private val _isAudioEnabled = MutableStateFlow(true)
    val isAudioEnabled = _isAudioEnabled.asStateFlow()

    private val _hasSdCard = MutableStateFlow(true)
    val hasSdCard = _hasSdCard.asStateFlow()

    private val _dialogState = MutableStateFlow<DialogState>(DialogState.None)
    val dialogState = _dialogState.asStateFlow()

    private val _recordingDuration = MutableStateFlow("00:00")
    val recordingDuration = _recordingDuration.asStateFlow()

    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Idle)
    val uiState: StateFlow<DashboardUiState> = _uiState

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState

    private val _recordings = MutableStateFlow<List<VideoFile>>(emptyList())
    val recordings: StateFlow<List<VideoFile>> = _recordings

    private val _localVideos = MutableStateFlow<List<VideoFile>>(emptyList())
    val localVideos = _localVideos.asStateFlow()

    private val _debugMessage = MutableStateFlow("Idle")
    val debugMessage = _debugMessage.asStateFlow()

    private val _lastStreamUrl = MutableStateFlow("")
    val lastStreamUrl = _lastStreamUrl.asStateFlow()

    val settings = settingsRepository.settingsFlow

    init {
        observeConnectionState()
        observeAutoConnectSettings()
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            connectionRepository.connectionState.collectLatest { state ->
                when (state) {
                    is CameraState.Connected -> {
                        val url = connectionRepository.activeProtocol.value?.getLiveStreamUrl().orEmpty()
                        _lastStreamUrl.value = url
                        _debugMessage.value = "Connected. Stream URL: $url"
                        _uiState.value = DashboardUiState.Streaming(url)
                        startStatusPolling()
                    }

                    is CameraState.Error -> {
                        _debugMessage.value = "Connection error: ${state.message}"
                        _uiState.value = DashboardUiState.Error(state.message)
                        stopStatusPolling()
                    }

                    is CameraState.Scanning -> {
                        _debugMessage.value = "Scanning / connecting to camera..."
                        _uiState.value = DashboardUiState.Loading
                    }

                    is CameraState.Connecting -> {
                        _debugMessage.value = "Connecting..."
                        _uiState.value = DashboardUiState.Loading
                    }

                    else -> {
                        _debugMessage.value = "Idle / disconnected"
                        _uiState.value = DashboardUiState.Idle
                        stopStatusPolling()
                    }
                }
            }
        }
    }

    private fun observeAutoConnectSettings() {
        viewModelScope.launch {
            settings.collectLatest { currentSettings ->
                if (currentSettings.wifiAutoConnect && _uiState.value is DashboardUiState.Idle) {
                    _debugMessage.value = "Auto-connect enabled, starting discovery..."
                    connect()
                }
            }
        }
    }

    private fun startStatusPolling() {
        if (statusPollingJob?.isActive == true) return

        statusPollingJob = viewModelScope.launch {
            _debugMessage.value = "Status polling started"

            while (true) {
                try {
                    val now = System.currentTimeMillis()
                    val timeSinceLastCommand = now - lastCommandTime

                    if (timeSinceLastCommand < 3000) {
                        delay(500)
                        continue
                    }

                    val status = connectionRepository.getDeviceStatus()

                    _hasSdCard.value = status.hasSdCard

                    if (status.isRecording && !_isRecording.value) {
                        _isRecording.value = true
                        if (recordingStartTimestamp == 0L) {
                            recordingStartTimestamp = System.currentTimeMillis()
                        }
                    } else if (!status.isRecording && _isRecording.value) {
                        _isRecording.value = false
                        recordingStartTimestamp = 0L
                        _recordingDuration.value = "00:00"
                    }

                    if (_isRecording.value) {
                        val seconds = (System.currentTimeMillis() - recordingStartTimestamp) / 1000
                        val minutes = seconds / 60
                        val remainingSeconds = seconds % 60
                        _recordingDuration.value = "%02d:%02d".format(minutes, remainingSeconds)
                    }

                    _debugMessage.value =
                        "Polling OK | rec=${_isRecording.value}, sd=${_hasSdCard.value}, stream=${_lastStreamUrl.value}"

                } catch (e: Exception) {
                    Log.e(TAG, "Status polling failed", e)
                    _debugMessage.value = "Status polling failed: ${e.message}"
                }

                delay(2000)
            }
        }
    }

    private fun stopStatusPolling() {
        statusPollingJob?.cancel()
        statusPollingJob = null
        _recordingDuration.value = if (_isRecording.value) _recordingDuration.value else "00:00"
    }

    fun toggleRecording() {
        Log.d(TAG, "Record button clicked, verifying state first...")
        lastCommandTime = System.currentTimeMillis()

        viewModelScope.launch {
            try {
                val status = connectionRepository.getDeviceStatus()
                val cameraIsRecording = status.isRecording
                val appThinksRecording = _isRecording.value

                Log.d(TAG, "Logic check -> App: $appThinksRecording | Camera: $cameraIsRecording")
                _debugMessage.value =
                    "Record toggle | app=$appThinksRecording camera=$cameraIsRecording"

                if (appThinksRecording) {
                    if (!cameraIsRecording) {
                        Log.d(TAG, "Sync fix: camera already stopped, updating UI only")
                        _isRecording.value = false
                        recordingStartTimestamp = 0L
                        _recordingDuration.value = "00:00"
                    } else {
                        Log.d(TAG, "Action: sending STOP command")
                        val success = connectionRepository.activeProtocol.value?.stopRecording() ?: false
                        if (success) {
                            _isRecording.value = false
                            recordingStartTimestamp = 0L
                            _recordingDuration.value = "00:00"
                            _dialogState.value = DialogState.Success("Recording Saved")
                            _debugMessage.value = "Recording stopped successfully"
                            delay(500)
                            _dialogState.value = DialogState.None
                        } else {
                            _dialogState.value = DialogState.Error("Failed to stop recording")
                            _debugMessage.value = "Failed to stop recording"
                        }
                    }
                } else {
                    if (cameraIsRecording) {
                        Log.d(TAG, "Sync fix: camera already recording, updating UI only")
                        _isRecording.value = true
                        if (recordingStartTimestamp == 0L) {
                            recordingStartTimestamp = System.currentTimeMillis()
                        }
                    } else {
                        Log.d(TAG, "Action: sending START command")
                        val success = connectionRepository.activeProtocol.value?.startRecording() ?: false
                        if (success) {
                            _isRecording.value = true
                            recordingStartTimestamp = System.currentTimeMillis()
                            _dialogState.value = DialogState.Success("Recording Started")
                            _debugMessage.value = "Recording started successfully"
                            delay(500)
                            _dialogState.value = DialogState.None
                        } else {
                            _dialogState.value = DialogState.Error("Failed to start recording")
                            _debugMessage.value = "Failed to start recording"
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "toggleRecording failed", e)
                _dialogState.value = DialogState.Error("Recording command failed")
                _debugMessage.value = "Recording command failed: ${e.message}"
            } finally {
                lastCommandTime = System.currentTimeMillis()
            }
        }
    }

    fun toggleAudio() {
        viewModelScope.launch {
            try {
                val newState = !_isAudioEnabled.value
                val success = connectionRepository.setAudioRecording(newState)
                if (success) {
                    _isAudioEnabled.value = newState
                    _debugMessage.value = "Audio recording set to $newState"
                } else {
                    _debugMessage.value = "Failed to change audio recording state"
                }
            } catch (e: Exception) {
                Log.e(TAG, "toggleAudio failed", e)
                _debugMessage.value = "Audio toggle failed: ${e.message}"
            }
        }
    }

    fun dismissDialog() {
        _dialogState.value = DialogState.None
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun connect() {
        viewModelScope.launch {
            _debugMessage.value = "Starting camera discovery..."
            _uiState.value = DashboardUiState.Loading
            connectionRepository.startDiscovery()
        }
    }

    fun disconnect() {
        stopStatusPolling()
        _debugMessage.value = "Disconnect requested"
        connectionRepository.disconnect()
        _uiState.value = DashboardUiState.Idle
    }

    fun fetchRecordings() {
        viewModelScope.launch {
            try {
                _debugMessage.value = "Fetching remote recordings..."
                val files = connectionRepository.getRecordings()
                _recordings.value = files
                _debugMessage.value = "Fetched ${files.size} remote file(s)"
            } catch (e: Exception) {
                Log.e(TAG, "fetchRecordings failed", e)
                _debugMessage.value = "Failed to fetch recordings: ${e.message}"
            }
        }
    }

    fun downloadVideo(file: VideoFile) {
        viewModelScope.launch {
            try {
                _debugMessage.value = "Downloading ${file.filename}..."
                _downloadState.value = DownloadState.Downloading(0f)

                connectionRepository.downloadFile(file.downloadUrl, file.filename) { progress ->
                    _downloadState.value = DownloadState.Downloading(progress)
                }

                _downloadState.value = DownloadState.Success(file.filename)
                _debugMessage.value = "Download complete: ${file.filename}"
                delay(3000)
                _downloadState.value = DownloadState.Idle
            } catch (e: Exception) {
                Log.e(TAG, "downloadVideo failed", e)
                _downloadState.value = DownloadState.Error("Failed to download ${file.filename}")
                _debugMessage.value = "Download failed: ${e.message}"
            }
        }
    }

    fun deleteFile(file: VideoFile) {
        viewModelScope.launch {
            try {
                _debugMessage.value = "Deleting ${file.filename}..."
                val success = connectionRepository.activeProtocol.value?.deleteFile(file.filename) ?: false

                if (success) {
                    fetchRecordings()
                    _dialogState.value = DialogState.Success("File deleted successfully")
                    _debugMessage.value = "Deleted ${file.filename}"
                    delay(2000)
                    _dialogState.value = DialogState.None
                } else {
                    _dialogState.value = DialogState.Error("Failed to delete file")
                    _debugMessage.value = "Failed to delete ${file.filename}"
                }
            } catch (e: Exception) {
                Log.e(TAG, "deleteFile failed", e)
                _dialogState.value = DialogState.Error("Failed to delete file")
                _debugMessage.value = "Delete failed: ${e.message}"
            }
        }
    }

    fun takePhoto() {
        viewModelScope.launch {
            try {
                _debugMessage.value = "Taking photo..."
                val success = connectionRepository.activeProtocol.value?.takePhoto() ?: false
                _debugMessage.value = if (success) {
                    "Photo capture command sent successfully"
                } else {
                    "Photo capture command failed"
                }
            } catch (e: Exception) {
                Log.e(TAG, "takePhoto failed", e)
                _debugMessage.value = "Photo capture failed: ${e.message}"
            }
        }
    }

    fun startRecording() {
        viewModelScope.launch {
            try {
                _debugMessage.value = "Manual start recording requested"
                connectionRepository.activeProtocol.value?.startRecording()
            } catch (e: Exception) {
                Log.e(TAG, "startRecording failed", e)
                _debugMessage.value = "Manual start failed: ${e.message}"
            }
        }
    }

    fun stopRecording() {
        viewModelScope.launch {
            try {
                _debugMessage.value = "Manual stop recording requested"
                connectionRepository.activeProtocol.value?.stopRecording()
            } catch (e: Exception) {
                Log.e(TAG, "stopRecording failed", e)
                _debugMessage.value = "Manual stop failed: ${e.message}"
            }
        }
    }

    fun loadLocalGallery(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val videoList = mutableListOf<VideoFile>()

                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                } else {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }

                val projection = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATE_ADDED
                )

                val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

                context.contentResolver.query(
                    collection,
                    projection,
                    null,
                    null,
                    sortOrder
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val name = cursor.getString(nameColumn)
                        val size = cursor.getLong(sizeColumn)
                        val date = cursor.getLong(dateColumn) * 1000L

                        val contentUri = ContentUris.withAppendedId(collection, id)

                        videoList.add(
                            VideoFile(
                                filename = name,
                                downloadUrl = contentUri.toString(),
                                thumbnailUrl = contentUri.toString(),
                                size = formatSize(size),
                                time = formatDate(date)
                            )
                        )
                    }
                }

                _localVideos.value = videoList
                _debugMessage.value = "Loaded ${videoList.size} local video(s)"
            } catch (e: Exception) {
                Log.e(TAG, "loadLocalGallery failed", e)
                _debugMessage.value = "Failed to load local gallery: ${e.message}"
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024 * 1024)
        return "${mb}MB"
    }

    private fun formatDate(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

    companion object {
        private const val TAG = "DashboardViewModel"
    }
}

sealed class DashboardUiState {
    object Idle : DashboardUiState()
    object Loading : DashboardUiState()
    data class Streaming(val url: String) : DashboardUiState()
    data class Error(val message: String) : DashboardUiState()
}

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val progress: Float) : DownloadState()
    data class Success(val filename: String) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

sealed class DialogState {
    object None : DialogState()
    data class Loading(val message: String) : DialogState()
    data class Success(val message: String) : DialogState()
    data class Error(val message: String) : DialogState()
}
