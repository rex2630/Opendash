package com.kooduXA.opendash.domain.model

data class CameraEndpoint(
    val ip: String,
    val source: EndpointSource,
    val label: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

enum class EndpointSource {
    AUTO_DISCOVERY,
    MANUAL_SETTINGS
}
