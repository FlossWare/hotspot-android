package org.flossware.hotspot.client.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
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
}
