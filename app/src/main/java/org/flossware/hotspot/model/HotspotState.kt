package org.flossware.hotspot.model

data class HotspotState(
    val isRunning: Boolean = false,
    val networkName: String = "",
    val passphrase: String = "",
    val configuredPassphrase: String = "",
    val socksHost: String = DEFAULT_HOST,
    val socksPort: Int = DEFAULT_SOCKS_PORT,
    val dnsPort: Int = DEFAULT_DNS_PORT,
    val connectedDevices: List<ConnectedDevice> = emptyList(),
    val error: String? = null,
    val bytesTransferred: Long = 0L,
    val bluetoothOptIn: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val bluetoothDeviceName: String = "",
    val bluetoothConnectedDevices: List<ConnectedDevice> = emptyList(),
    val usbConnected: Boolean = false,
    val dnsCacheHits: Long = 0L,
    val httpCacheHits: Long = 0L,
    val dataSaved: Long = 0L,
    val uptimeSeconds: Long = 0L,
    val isIdle: Boolean = false,
    val wifiDirectAvailable: Boolean = true,
    val bluetoothAvailable: Boolean = true,
    val usbAvailable: Boolean = true,
    val mobileDataAvailable: Boolean = true,
    val permissionsDenied: Boolean = false,
    val bluetoothOnlyMode: Boolean = false,
) {
    val socksAddress: String get() = "$socksHost:$socksPort"
    val dnsAddress: String get() = "$socksHost:$dnsPort"

    /** True when Wi-Fi Direct failed but Bluetooth is available as a fallback. */
    val canFallbackToBluetooth: Boolean
        get() = !wifiDirectAvailable && bluetoothAvailable && !isRunning

    companion object {
        const val DEFAULT_HOST = "192.168.49.1"
        const val DEFAULT_SOCKS_PORT = 1080
        const val DEFAULT_DNS_PORT = 5353
    }
}
