package org.flossware.hotspot.service

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.app.Service
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.flossware.hotspot.model.HotspotState
import java.net.InetAddress

/**
 * Foreground service that orchestrates the hotspot subsystems.
 *
 * All domain work is delegated to focused managers:
 * - [NetworkManager] — mobile-data callbacks and upstream DNS
 * - [ProxyManager]   — SOCKS5 servers, DNS relay, HTTP cache
 * - [BluetoothManager] — Bluetooth RFCOMM tunnel lifecycle
 * - [NotificationHelper] — foreground notification building
 * - [WifiDirectManager] — Wi-Fi Direct group creation (pre-existing)
 *
 * This class is responsible only for lifecycle ordering, state
 * composition, and the Android [Service] contract.
 */
class HotspotService : Service() {

    private var scope = CoroutineScope(Dispatchers.Main + Job())
    private val wifiDirectManager = WifiDirectManager()
    private lateinit var networkManager: NetworkManager
    private val proxyManager = ProxyManager()
    private val bluetoothManager = BluetoothManager()
    private lateinit var notificationHelper: NotificationHelper

    override fun onCreate() {
        super.onCreate()
        networkManager = NetworkManager(this)
        notificationHelper = NotificationHelper(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startHotspot()
            ACTION_STOP -> stopHotspot()
        }
        return START_NOT_STICKY
    }

    private fun startHotspot() {
        Log.i(TAG, "Starting hotspot service")
        scope.cancel()
        scope = CoroutineScope(Dispatchers.Main + Job())
        networkManager.onNetworkLost = {
            _state.value = _state.value.copy(error = "Mobile data connection lost")
        }
        networkManager.unregister()
        startForeground(NOTIFICATION_ID, notificationHelper.build(0))
        networkManager.register()

        scope.launch {
            wifiDirectManager.state.collect { wifiState ->
                when (wifiState) {
                    is WifiDirectState.GroupCreated -> {
                        startServices(wifiState)
                        updateState(wifiState)
                    }
                    is WifiDirectState.Error -> {
                        _state.value = _state.value.copy(
                            isRunning = false,
                            error = wifiState.message,
                        )
                    }
                    WifiDirectState.Idle -> {}
                }
            }
        }

        scope.launch {
            while (true) {
                delay(2000)
                if (!proxyManager.isRunning) continue
                val current = _state.value
                if (current.isRunning) {
                    _state.value = current.copy(
                        bytesTransferred = proxyManager.bytesTransferred,
                        dnsCacheHits = proxyManager.dnsCacheHits,
                        httpCacheHits = proxyManager.httpCacheHits,
                        dataSaved = proxyManager.dataSaved,
                    )
                    wifiDirectManager.refreshPeers()
                }
            }
        }

        wifiDirectManager.start(this)
    }

    private fun startServices(wifiState: WifiDirectState.GroupCreated) {
        if (proxyManager.isRunning) return

        val bindAddr = InetAddress.getByName(wifiState.groupOwnerAddress)

        proxyManager.start(
            bindAddress = bindAddr,
            socketFactoryProvider = { networkManager.socketFactory },
            upstreamDnsProvider = { networkManager.upstreamDns ?: InetAddress.getByName("8.8.8.8") },
            socketBinder = { sock -> networkManager.bindSocket(sock) },
        )

        bluetoothManager.start(this, scope)

        scope.launch {
            bluetoothManager.status.collect { status ->
                _state.value = _state.value.copy(
                    bluetoothEnabled = status.enabled,
                    bluetoothDeviceName = status.deviceName,
                )
            }
        }

        scope.launch {
            bluetoothManager.connectedDevices.collect { devices ->
                _state.value = _state.value.copy(bluetoothConnectedDevices = devices)
            }
        }
    }

    private fun updateState(wifiState: WifiDirectState.GroupCreated) {
        _state.value = HotspotState(
            isRunning = true,
            networkName = wifiState.networkName,
            passphrase = wifiState.passphrase,
            socksHost = wifiState.groupOwnerAddress,
            connectedDevices = wifiState.connectedDevices,
            bytesTransferred = proxyManager.bytesTransferred,
        )
        notificationHelper.update(wifiState.connectedDevices.size)
    }

    private fun stopHotspot() {
        Log.i(TAG, "Stopping hotspot service")
        bluetoothManager.stop()
        proxyManager.stop()
        wifiDirectManager.stop()
        networkManager.unregister()
        _state.value = HotspotState()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopHotspot()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HotspotService"
        const val ACTION_START = "org.flossware.hotspot.START"
        const val ACTION_STOP = "org.flossware.hotspot.STOP"
        const val CHANNEL_ID = "hotspot_service"
        const val NOTIFICATION_ID = 1

        private val _state = MutableStateFlow(HotspotState())
        val state: StateFlow<HotspotState> = _state.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, HotspotService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, HotspotService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
