package org.flossware.hotspot.client.viewmodel

import android.app.Application
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow
import org.flossware.hotspot.client.model.VpnState
import org.flossware.hotspot.client.service.TunnelService

class ClientViewModel(application: Application) : AndroidViewModel(application) {

    val vpnState: StateFlow<VpnState> = TunnelService.state

    fun prepareVpn(): Intent? {
        return VpnService.prepare(getApplication())
    }

    fun connect(socksHost: String, socksPort: Int) {
        TunnelService.connect(getApplication(), socksHost, socksPort)
    }

    fun disconnect() {
        TunnelService.disconnect(getApplication())
    }
}
