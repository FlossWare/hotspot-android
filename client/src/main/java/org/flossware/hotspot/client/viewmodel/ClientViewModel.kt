package org.flossware.hotspot.client.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow
import org.flossware.hotspot.client.R
import org.flossware.hotspot.client.model.VpnState
import org.flossware.hotspot.client.service.TunnelService

data class BluetoothDeviceInfo(val name: String, val address: String)
data class UsbDeviceInfo(val name: String, val deviceName: String)

class ClientViewModel(application: Application) : AndroidViewModel(application) {

    val vpnState: StateFlow<VpnState> = TunnelService.state

    init {
        detectTransportAvailability()
    }

    fun prepareVpn(): Intent? {
        return VpnService.prepare(getApplication())
    }

    fun connect(socksHost: String, socksPort: Int) {
        TunnelService.connect(getApplication(), socksHost, socksPort)
    }

    fun connectBluetooth(deviceAddress: String) {
        TunnelService.connectBluetooth(getApplication(), deviceAddress)
    }

    fun connectUsb(deviceName: String) {
        TunnelService.connectUsb(getApplication(), deviceName)
    }

    fun connectWifi(networkName: String, passphrase: String, socksHost: String, socksPort: Int) {
        TunnelService.connectWifi(getApplication(), networkName, passphrase, socksHost, socksPort)
    }

    fun disconnect() {
        TunnelService.disconnect(getApplication())
    }

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDeviceInfo> {
        val btManager = getApplication<Application>()
            .getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        val unknownName = getApplication<Application>().getString(R.string.unknown_device)
        return adapter.bondedDevices.map { device ->
            BluetoothDeviceInfo(
                name = device.name ?: unknownName,
                address = device.address,
            )
        }
    }

    fun getUsbDevices(): List<UsbDeviceInfo> {
        val usbManager = getApplication<Application>()
            .getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
        return usbManager.deviceList.values.map { device ->
            UsbDeviceInfo(
                name = device.productName ?: "USB Device",
                deviceName = device.deviceName,
            )
        }
    }

    /**
     * Detects which transports are available and updates the shared VPN state
     * so the UI can show appropriate guidance.
     */
    private fun detectTransportAvailability() {
        val app = getApplication<Application>()
        val pm = app.packageManager

        val wifiAvailable = pm.hasSystemFeature(PackageManager.FEATURE_WIFI)

        val btManager = app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAvailable = btManager?.adapter != null

        val usbManager = app.getSystemService(Context.USB_SERVICE) as? UsbManager
        val usbAvailable = usbManager != null

        TunnelService.updateTransportAvailability(
            wifiAvailable = wifiAvailable,
            bluetoothAvailable = bluetoothAvailable,
            usbAvailable = usbAvailable,
        )
    }
}
