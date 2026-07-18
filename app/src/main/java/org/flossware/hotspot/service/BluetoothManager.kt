package org.flossware.hotspot.service

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.flossware.hotspot.model.ConnectedDevice

/**
 * Aggregated Bluetooth status exposed as a single atomic update so that
 * consumers never see an intermediate state where [enabled] changed but
 * [deviceName] did not.
 */
data class BluetoothStatus(
    val enabled: Boolean = false,
    val deviceName: String = "",
)

/**
 * Manages the [BluetoothServer] lifecycle and collects its state into
 * higher-level [BluetoothStatus] and [connectedDevices] flows.
 *
 * HotspotService delegates all Bluetooth concerns here.
 */
class BluetoothManager {

    private var server: BluetoothServer? = null

    private val _status = MutableStateFlow(BluetoothStatus())
    val status: StateFlow<BluetoothStatus> = _status.asStateFlow()

    private val _connectedDevices = MutableStateFlow<List<ConnectedDevice>>(emptyList())
    val connectedDevices: StateFlow<List<ConnectedDevice>> = _connectedDevices.asStateFlow()

    /**
     * Creates and starts a [BluetoothServer], then launches collectors on [scope]
     * that map the server's raw [BluetoothState] into [status] and [connectedDevices].
     */
    fun start(context: Context, scope: CoroutineScope) {
        server = BluetoothServer().also { bt ->
            bt.start(context)

            scope.launch {
                bt.state.collect { btState ->
                    _status.value = BluetoothStatus(
                        enabled = btState is BluetoothState.Listening,
                        deviceName = (btState as? BluetoothState.Listening)?.deviceName ?: "",
                    )
                }
            }

            scope.launch {
                bt.connectedDevices.collect { devices ->
                    _connectedDevices.value = devices
                }
            }
        }
    }

    /** Stops the underlying [BluetoothServer] and resets exposed state. */
    fun stop() {
        server?.stop()
        server = null
        _status.value = BluetoothStatus()
        _connectedDevices.value = emptyList()
    }
}
