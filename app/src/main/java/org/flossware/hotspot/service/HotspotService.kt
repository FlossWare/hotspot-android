package org.flossware.hotspot.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import javax.net.SocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.flossware.hotspot.MainActivity
import org.flossware.hotspot.R
import org.flossware.hotspot.model.HotspotState
import org.flossware.hotspot.proxy.DnsRelay
import org.flossware.hotspot.proxy.HttpCache
import org.flossware.hotspot.proxy.Socks5Server
import java.net.InetAddress

class HotspotService : Service() {

    private var scope = CoroutineScope(Dispatchers.Main + Job())
    private val wifiDirectManager = WifiDirectManager()
    private var socksServer: Socks5Server? = null
    private var localSocksServer: Socks5Server? = null
    private var dnsRelay: DnsRelay? = null
    private var bluetoothServer: BluetoothServer? = null
    private val httpCache = HttpCache()
    @Volatile private var mobileNetwork: Network? = null
    @Volatile private var upstreamDns: InetAddress? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var startTimeElapsed: Long = 0L
    private var lastBytesTransferred: Long = 0L
    private var idlePolls: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startHotspot()
            ACTION_STOP -> stopHotspot()
        }
        return START_NOT_STICKY
    }

    private fun startHotspot() {
        scope.cancel()
        scope = CoroutineScope(Dispatchers.Main + Job())
        unregisterMobileNetwork()
        startForeground(NOTIFICATION_ID, buildNotification(0))
        registerMobileNetwork()
        acquireWakeLock()
        startTimeElapsed = SystemClock.elapsedRealtime()
        lastBytesTransferred = 0L
        idlePolls = 0

        scope.launch {
            wifiDirectManager.state.collect { wifiState ->
                when (wifiState) {
                    is WifiDirectState.GroupCreated -> {
                        startSocksAndDns(wifiState)
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
                val currentBytes = socksServer?.bytesTransferred ?: 0L
                val isIdle = currentBytes == lastBytesTransferred && currentBytes > 0L
                if (isIdle) {
                    idlePolls++
                } else {
                    idlePolls = 0
                }
                lastBytesTransferred = currentBytes

                val pollInterval = when {
                    idlePolls >= IDLE_THRESHOLD_POLLS -> IDLE_POLL_MS
                    else -> ACTIVE_POLL_MS
                }

                if (idlePolls == IDLE_THRESHOLD_POLLS) {
                    releaseWakeLock()
                } else if (idlePolls == 0 && wakeLock?.isHeld != true) {
                    acquireWakeLock()
                }

                delay(pollInterval)
                val socks = socksServer ?: continue
                val current = _state.value
                if (current.isRunning) {
                    val dns = dnsRelay
                    val uptimeMs = SystemClock.elapsedRealtime() - startTimeElapsed
                    _state.value = current.copy(
                        bytesTransferred = socks.bytesTransferred,
                        dnsCacheHits = dns?.cacheHits ?: 0L,
                        httpCacheHits = httpCache.hits,
                        dataSaved = httpCache.dataSaved,
                        uptimeSeconds = uptimeMs / 1000,
                        isIdle = idlePolls >= IDLE_THRESHOLD_POLLS,
                    )
                    wifiDirectManager.refreshPeers()
                }
            }
        }

        wifiDirectManager.start(this)
    }

    private fun startSocksAndDns(wifiState: WifiDirectState.GroupCreated) {
        if (socksServer != null) return

        val bindAddr = InetAddress.getByName(wifiState.groupOwnerAddress)
        val socketProvider = { mobileNetwork?.socketFactory ?: SocketFactory.getDefault() }

        socksServer = Socks5Server(
            bindAddress = bindAddr,
            port = HotspotState.DEFAULT_SOCKS_PORT,
            socketFactoryProvider = socketProvider,
            httpCache = httpCache,
        ).also { it.start() }

        localSocksServer = Socks5Server(
            bindAddress = InetAddress.getLoopbackAddress(),
            port = HotspotState.DEFAULT_SOCKS_PORT,
            socketFactoryProvider = socketProvider,
            httpCache = httpCache,
        ).also { it.start() }

        dnsRelay = DnsRelay(
            bindAddress = bindAddr,
            listenPort = HotspotState.DEFAULT_DNS_PORT,
            upstreamDnsProvider = { upstreamDns ?: InetAddress.getByName("8.8.8.8") },
            socketBinder = { sock -> mobileNetwork?.bindSocket(sock) },
        ).also { it.start() }

        bluetoothServer = BluetoothServer().also { it.start(this) }

        scope.launch {
            bluetoothServer?.state?.collect { btState ->
                _state.value = _state.value.copy(
                    bluetoothEnabled = btState is BluetoothState.Listening,
                    bluetoothDeviceName = (btState as? BluetoothState.Listening)?.deviceName ?: "",
                )
            }
        }

        scope.launch {
            bluetoothServer?.connectedDevices?.collect { devices ->
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
            bytesTransferred = socksServer?.bytesTransferred ?: 0,
        )
        updateNotification(wifiState.connectedDevices.size)
    }

    private fun stopHotspot() {
        bluetoothServer?.stop()
        bluetoothServer = null
        dnsRelay?.stop()
        dnsRelay = null
        localSocksServer?.stop()
        localSocksServer = null
        socksServer?.stop()
        socksServer = null
        httpCache.clear()
        wifiDirectManager.stop()
        unregisterMobileNetwork()
        releaseWakeLock()
        _state.value = HotspotState()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun registerMobileNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mobileNetwork = network
                Log.i(TAG, "Mobile network available")
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                if (network == mobileNetwork) {
                    upstreamDns = lp.dnsServers.firstOrNull()
                }
            }

            override fun onLost(network: Network) {
                if (network == mobileNetwork) {
                    mobileNetwork = null
                    _state.value = _state.value.copy(
                        error = "Mobile data connection lost",
                    )
                }
            }
        }.also {
            cm.registerNetworkCallback(request, it)
        }
    }

    private fun unregisterMobileNetwork() {
        networkCallback?.let { cb ->
            try {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb)
            } catch (_: Exception) {
            }
        }
        networkCallback = null
    }

    private fun buildNotification(deviceCount: Int): android.app.Notification {
        val stopIntent = Intent(this, HotspotService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, deviceCount))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(openPending)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.notification_action_stop),
                stopPending,
            )
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(deviceCount: Int) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(deviceCount))
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "FlossWareHotspot::ProxyWakeLock",
        ).apply {
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released (idle)")
            }
        }
        wakeLock = null
    }

    override fun onDestroy() {
        stopHotspot()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "org.flossware.hotspot.START"
        const val ACTION_STOP = "org.flossware.hotspot.STOP"
        const val CHANNEL_ID = "hotspot_service"
        const val NOTIFICATION_ID = 1

        private const val TAG = "HotspotService"
        internal const val ACTIVE_POLL_MS = 2000L
        internal const val IDLE_POLL_MS = 10000L
        internal const val IDLE_THRESHOLD_POLLS = 15 // 15 * 2s = 30s of no data before idle
        private const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L // 4 hours max

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
