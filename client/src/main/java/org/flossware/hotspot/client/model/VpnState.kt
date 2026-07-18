package org.flossware.hotspot.client.model

enum class Transport { WIFI_DIRECT, BLUETOOTH, USB }

enum class ConnectionErrorType {
    NONE,
    HOST_NOT_FOUND,
    AUTH_FAILED,
    TIMEOUT,
    VPN_DENIED,
    NO_TRANSPORTS,
    GENERIC,
}

data class VpnState(
    val isConnected: Boolean = false,
    val socksHost: String = DEFAULT_SOCKS_HOST,
    val socksPort: Int = DEFAULT_SOCKS_PORT,
    val transport: Transport = Transport.WIFI_DIRECT,
    val error: String? = null,
    val errorType: ConnectionErrorType = ConnectionErrorType.NONE,
    val wifiAvailable: Boolean = true,
    val bluetoothAvailable: Boolean = true,
    val usbAvailable: Boolean = true,
) {
    val socksAddress: String get() = "$socksHost:$socksPort"

    /** True when no transport methods are available at all. */
    val noTransportsAvailable: Boolean
        get() = !wifiAvailable && !bluetoothAvailable && !usbAvailable

    companion object {
        const val DEFAULT_SOCKS_HOST = "192.168.49.1"
        const val DEFAULT_SOCKS_PORT = 1080
    }
}
