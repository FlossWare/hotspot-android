package org.flossware.hotspot.model

data class HotspotState(
    val isRunning: Boolean = false,
    val networkName: String = "",
    val passphrase: String = "",
    val proxyHost: String = DEFAULT_HOST,
    val proxyPort: Int = DEFAULT_PROXY_PORT,
    val dnsPort: Int = DEFAULT_DNS_PORT,
    val connectedDevices: List<ConnectedDevice> = emptyList(),
    val error: String? = null,
    val bytesTransferred: Long = 0L,
) {
    val proxyAddress: String get() = "$proxyHost:$proxyPort"
    val dnsAddress: String get() = "$proxyHost:$dnsPort"

    companion object {
        const val DEFAULT_HOST = "192.168.49.1"
        const val DEFAULT_PROXY_PORT = 8080
        const val DEFAULT_DNS_PORT = 5353
    }
}
