package org.flossware.hotspot.service

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.flossware.hotspot.model.ConnectedDevice

sealed class WifiDirectState {
    data object Idle : WifiDirectState()
    data class GroupCreated(
        val networkName: String,
        val passphrase: String,
        val groupOwnerAddress: String,
        val connectedDevices: List<ConnectedDevice> = emptyList(),
    ) : WifiDirectState()
    data class Error(val message: String) : WifiDirectState()
}

class WifiDirectManager {
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var context: Context? = null

    private val _state = MutableStateFlow<WifiDirectState>(WifiDirectState.Idle)
    val state: StateFlow<WifiDirectState> = _state.asStateFlow()

    @SuppressLint("MissingPermission")
    fun start(ctx: Context) {
        context = ctx
        manager = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (manager == null) {
            _state.value = WifiDirectState.Error("Wi-Fi Direct not supported")
            return
        }

        channel = manager!!.initialize(ctx, Looper.getMainLooper(), null)

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val wifiState = intent.getIntExtra(
                            WifiP2pManager.EXTRA_WIFI_STATE,
                            WifiP2pManager.WIFI_P2P_STATE_DISABLED,
                        )
                        if (wifiState != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                            _state.value = WifiDirectState.Error("Wi-Fi Direct is disabled")
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        requestGroupInfo()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        }
        ctx.registerReceiver(receiver, filter)

        createGroup()
    }

    fun stop() {
        removeGroup()
        context?.let { ctx ->
            receiver?.let { ctx.unregisterReceiver(it) }
        }
        receiver = null
        channel = null
        manager = null
        context = null
        _state.value = WifiDirectState.Idle
    }

    fun refreshPeers() {
        requestGroupInfo()
    }

    @SuppressLint("MissingPermission")
    private fun createGroup() {
        val ch = channel ?: return
        val mgr = manager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val config = WifiP2pConfig.Builder()
                .setNetworkName("DIRECT-FW-FlossHotspot")
                .setPassphrase("FlossWare2024")
                .build()
            mgr.createGroup(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "Group created (custom config)")
                    requestGroupInfo()
                }
                override fun onFailure(reason: Int) {
                    createGroupLegacy()
                }
            })
        } else {
            createGroupLegacy()
        }
    }

    @SuppressLint("MissingPermission")
    private fun createGroupLegacy() {
        val ch = channel ?: return
        val mgr = manager ?: return

        mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Group created (legacy)")
                requestGroupInfo()
            }
            override fun onFailure(reason: Int) {
                val msg = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct not supported"
                    WifiP2pManager.BUSY -> "Wi-Fi Direct busy, try again"
                    WifiP2pManager.ERROR -> "Internal error creating group"
                    else -> "Failed to create group (reason: $reason)"
                }
                _state.value = WifiDirectState.Error(msg)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun removeGroup() {
        val ch = channel ?: return
        val mgr = manager ?: return
        mgr.removeGroup(ch, null)
    }

    @SuppressLint("MissingPermission")
    private fun requestGroupInfo() {
        val ch = channel ?: return
        val mgr = manager ?: return

        mgr.requestGroupInfo(ch) { group: WifiP2pGroup? ->
            if (group != null && group.isGroupOwner) {
                val devices = group.clientList.map { device ->
                    ConnectedDevice(
                        macAddress = device.deviceAddress,
                        deviceName = device.deviceName.ifEmpty { "Unknown" },
                    )
                }
                _state.value = WifiDirectState.GroupCreated(
                    networkName = group.networkName,
                    passphrase = group.passphrase,
                    groupOwnerAddress = "192.168.49.1",
                    connectedDevices = devices,
                )
            }
        }
    }

    companion object {
        private const val TAG = "WifiDirectManager"
    }
}
