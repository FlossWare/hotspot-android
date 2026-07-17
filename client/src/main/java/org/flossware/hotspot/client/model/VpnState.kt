package org.flossware.hotspot.client.model

data class VpnState(
    val isConnected: Boolean = false,
    val socksHost: String = DEFAULT_SOCKS_HOST,
    val socksPort: Int = DEFAULT_SOCKS_PORT,
    val error: String? = null,
) {
    val socksAddress: String get() = "$socksHost:$socksPort"

    companion object {
        const val DEFAULT_SOCKS_HOST = "192.168.49.1"
        const val DEFAULT_SOCKS_PORT = 1080
    }
}
