package com.kooduXA.opendash.data.repository

import com.kooduXA.opendash.domain.model.CameraConnectionSettings
import kotlinx.coroutines.flow.Flow

interface CameraConnectionSettingsRepository {
    fun settingsFlow(): Flow<CameraConnectionSettings>
    suspend fun getSettings(): CameraConnectionSettings
    suspend fun updateSettings(settings: CameraConnectionSettings)
}
