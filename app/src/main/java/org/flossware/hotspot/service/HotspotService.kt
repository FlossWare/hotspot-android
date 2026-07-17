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
import org.flossware.hotspot.proxy.ProxyServer
import java.net.InetAddress

class HotspotService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val wifiDirectManager = WifiDirectManager()
    private var proxyServer: ProxyServer? = null
    private var dnsRelay: DnsRelay? = null
    private var mobileNetwork: Network? = null
    private var upstreamDns: InetAddress? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startHotspot()
            ACTION_STOP -> stopHotspot()
        }
        return START_NOT_STICKY
    }

    private fun startHotspot() {
        startForeground(NOTIFICATION_ID, buildNotification(0))
        registerMobileNetwork()

        scope.launch {
            wifiDirectManager.state.collect { wifiState ->
                when (wifiState) {
                    is WifiDirectState.GroupCreated -> {
                        startProxyAndDns(wifiState)
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
                val proxy = proxyServer ?: continue
                val current = _state.value
                if (current.isRunning) {
                    _state.value = current.copy(
                        bytesTransferred = proxy.bytesTransferred,
                    )
                    wifiDirectManager.refreshPeers()
                }
            }
        }

        wifiDirectManager.start(this)
    }

    private fun startProxyAndDns(wifiState: WifiDirectState.GroupCreated) {
        if (proxyServer != null) return

        val bindAddr = InetAddress.getByName(wifiState.groupOwnerAddress)

        proxyServer = ProxyServer(
            bindAddress = bindAddr,
            port = HotspotState.DEFAULT_PROXY_PORT,
            socketFactoryProvider = { mobileNetwork?.socketFactory ?: SocketFactory.getDefault() },
        ).also { it.start() }

        dnsRelay = DnsRelay(
            bindAddress = bindAddr,
            listenPort = HotspotState.DEFAULT_DNS_PORT,
            upstreamDnsProvider = { upstreamDns ?: InetAddress.getByName("8.8.8.8") },
            socketBinder = { sock -> mobileNetwork?.bindSocket(sock) },
        ).also { it.start() }
    }

    private fun updateState(wifiState: WifiDirectState.GroupCreated) {
        _state.value = HotspotState(
            isRunning = true,
            networkName = wifiState.networkName,
            passphrase = wifiState.passphrase,
            proxyHost = wifiState.groupOwnerAddress,
            connectedDevices = wifiState.connectedDevices,
            bytesTransferred = proxyServer?.bytesTransferred ?: 0,
        )
        updateNotification(wifiState.connectedDevices.size)
    }

    private fun stopHotspot() {
        dnsRelay?.stop()
        dnsRelay = null
        proxyServer?.stop()
        proxyServer = null
        wifiDirectManager.stop()
        unregisterMobileNetwork()
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
