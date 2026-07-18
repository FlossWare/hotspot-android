package org.flossware.hotspot.client.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow
import org.flossware.hotspot.client.R
import org.flossware.hotspot.client.model.VpnState
import org.flossware.hotspot.client.service.TunnelService

data class BluetoothDeviceInfo(val name: String, val address: String)

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
}
