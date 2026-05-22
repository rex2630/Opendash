package com.kooduXA.opendash.domain.model

data class CameraConnectionSettings(
    val connectionMode: ConnectionMode = ConnectionMode.AUTO_DISCOVERY,
    val manualIp: String = "",
    val preferManualFirst: Boolean = true
)
