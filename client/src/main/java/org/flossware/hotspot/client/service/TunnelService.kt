package org.flossware.hotspot.client.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.flossware.hotspot.client.ClientApp
import org.flossware.hotspot.client.MainActivity
import org.flossware.hotspot.client.R
import org.flossware.hotspot.client.model.VpnState
import org.flossware.hotspot.client.tunnel.SocksTunnel

class TunnelService : VpnService() {

    private var tunInterface: ParcelFileDescriptor? = null
    private var socksTunnel: SocksTunnel? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val host = intent.getStringExtra(EXTRA_SOCKS_HOST) ?: VpnState.DEFAULT_SOCKS_HOST
                val port = intent.getIntExtra(EXTRA_SOCKS_PORT, VpnState.DEFAULT_SOCKS_PORT)
                connect(host, port)
            }
            ACTION_DISCONNECT -> disconnect()
        }
        return START_NOT_STICKY
    }

    private fun connect(socksHost: String, socksPort: Int) {
        if (tunInterface != null) return

        startForeground(NOTIFICATION_ID, buildNotification())

        try {
            val tun = Builder()
                .setSession("FlossWare Tunnel")
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(socksHost)
                .setMtu(1500)
                .setBlocking(true)
                .establish() ?: run {
                    _state.value = _state.value.copy(error = "VPN permission denied")
                    stopSelf()
                    return
                }

            tunInterface = tun

            socksTunnel = SocksTunnel(
                tunFd = tun.fd,
                socksHost = socksHost,
                socksPort = socksPort,
                protector = { fd -> protect(fd) },
            ).also { it.start() }

            _state.value = VpnState(
                isConnected = true,
                socksHost = socksHost,
                socksPort = socksPort,
            )
            Log.i(TAG, "VPN tunnel established to $socksHost:$socksPort")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish tunnel", e)
            _state.value = _state.value.copy(
                isConnected = false,
                error = e.message ?: "Connection failed",
            )
            disconnect()
        }
    }

    private fun disconnect() {
        socksTunnel?.stop()
        socksTunnel = null
        tunInterface?.close()
        tunInterface = null
        _state.value = VpnState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "VPN tunnel disconnected")
    }

    private fun buildNotification(): android.app.Notification {
        val disconnectIntent = Intent(this, TunnelService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPending = PendingIntent.getService(
            this, 0, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, ClientApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(openPending)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.notification_action_disconnect),
                disconnectPending,
            )
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CONNECT = "org.flossware.hotspot.client.CONNECT"
        const val ACTION_DISCONNECT = "org.flossware.hotspot.client.DISCONNECT"
        const val EXTRA_SOCKS_HOST = "socks_host"
        const val EXTRA_SOCKS_PORT = "socks_port"
        const val NOTIFICATION_ID = 1

        private const val TAG = "TunnelService"

        private val _state = MutableStateFlow(VpnState())
        val state: StateFlow<VpnState> = _state.asStateFlow()

        fun connect(context: Context, socksHost: String, socksPort: Int) {
            val intent = Intent(context, TunnelService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_SOCKS_HOST, socksHost)
                putExtra(EXTRA_SOCKS_PORT, socksPort)
            }
            context.startForegroundService(intent)
        }

        fun disconnect(context: Context) {
            val intent = Intent(context, TunnelService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startService(intent)
        }
    }
}
