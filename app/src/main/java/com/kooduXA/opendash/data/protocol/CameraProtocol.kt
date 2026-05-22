package com.kooduXA.opendash.data.protocol

import com.kooduXA.opendash.domain.model.CameraEndpoint
import com.kooduXA.opendash.domain.model.CameraState
import com.kooduXA.opendash.domain.model.StorageInfo
import com.kooduXA.opendash.domain.model.VideoFile
import kotlinx.coroutines.flow.StateFlow

interface CameraProtocol {
    val protocolName: String
    val connectionState: StateFlow<CameraState>

    /**
     * Lightweight probe used during discovery or manual connection validation.
     * Should return true only when this protocol recognizes the camera at the given endpoint.
     */
    suspend fun canHandle(endpoint: CameraEndpoint): Boolean

    /**
     * Establishes a working session after a successful probe.
     */
    suspend fun connect(endpoint: CameraEndpoint): Boolean

    suspend fun getLiveStreamUrl(): String
    suspend fun startHeartbeat()

    suspend fun startRecording(): Boolean
    suspend fun stopRecording(): Boolean
    suspend fun takePhoto(): Boolean

    fun disconnect()

    suspend fun getFileList(): List<VideoFile>
    suspend fun deleteFile(filename: String): Boolean
    suspend fun getStorageInfo(): StorageInfo?
    suspend fun formatSdCard(): Boolean
}
