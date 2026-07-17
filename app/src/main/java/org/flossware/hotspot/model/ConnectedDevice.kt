package org.flossware.hotspot.model

data class ConnectedDevice(
    val macAddress: String,
    val deviceName: String,
    val ipAddress: String? = null,
)
