package com.kooduXA.opendash.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kooduXA.opendash.data.repository.ConnectionRepository
import com.kooduXA.opendash.data.repository.SettingsRepository
import com.kooduXA.opendash.domain.model.StorageInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val connectionRepository: ConnectionRepository
) : ViewModel() {

    val settings = repository.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SettingsRepository.AppSettings()
        )

    private val _storageInfo = MutableStateFlow<StorageInfo?>(null)
    val storageInfo = _storageInfo.asStateFlow()

    private val _isFetchingStorageInfo = MutableStateFlow(false)
    val isFetchingStorageInfo = _isFetchingStorageInfo.asStateFlow()

    private val _formatResult = MutableStateFlow<Boolean?>(null)
    val formatResult = _formatResult.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    fun updateSetting(key: String, value: Any) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val current = settings.value
                val updated = when (key) {
                    "nightMode" -> current.copy(nightMode = value as Boolean)
                    "videoResolution" -> current.copy(videoResolution = value as String)
                    "loopRecording" -> current.copy(loopRecording = value as Boolean)
                    "loopDuration" -> current.copy(loopDuration = value as Int)
                    "audioRecording" -> current.copy(audioRecording = value as Boolean)
                    "impactDetection" -> current.copy(impactDetection = value as Boolean)
                    "motionDetection" -> current.copy(motionDetection = value as Boolean)
                    "wifiSSID" -> current.copy(wifiSSID = value as String)
                    "wifiPassword" -> current.copy(wifiPassword = value as String)
                    "wifiAutoConnect" -> current.copy(wifiAutoConnect = value as Boolean)
                    "cameraIp" -> current.copy(cameraIp = value as String)
                    else -> current
                }

                repository.updateSettings(updated)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun resetToDefaults() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.resetToDefaults()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun saveAllSettings(settings: SettingsRepository.AppSettings) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.updateSettings(settings)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun fetchStorageInfo() {
        viewModelScope.launch {
            _isFetchingStorageInfo.value = true
            try {
                _storageInfo.value = null
                _storageInfo.value = connectionRepository.getStorageInfo()
            } finally {
                _isFetchingStorageInfo.value = false
            }
        }
    }

    fun formatSdCard() {
        viewModelScope.launch {
            _formatResult.value = connectionRepository.formatSdCard()
        }
    }

    fun clearFormatResult() {
        _formatResult.value = null
    }

    fun updateResolution(res: String) {
        viewModelScope.launch {
            repository.updateSettings(settings.value.copy(videoResolution = res))
        }
    }

    fun toggleLoopRecording(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateSettings(settings.value.copy(loopRecording = enabled))
        }
    }

    fun toggleAudio(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateSettings(settings.value.copy(audioRecording = enabled))
        }
    }

    fun toggleAutoConnect(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateSettings(settings.value.copy(wifiAutoConnect = enabled))
        }
    }

    fun updateCameraIp(ip: String) {
        viewModelScope.launch {
            repository.updateSettings(settings.value.copy(cameraIp = ip))
        }
    }

    fun toggleDarkTheme(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateSettings(settings.value.copy(nightMode = enabled))
        }
    }

    fun resetSettings() {
        viewModelScope.launch {
            repository.updateSettings(SettingsRepository.AppSettings())
        }
    }

    fun updateWifi(ssid: String, pass: String) {
        viewModelScope.launch {
            repository.updateSettings(
                settings.value.copy(
                    wifiSSID = ssid,
                    wifiPassword = pass
                )
            )

            connectionRepository.updateWifi(ssid, pass)
        }
    }
}
