package org.flossware.hotspot.client.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.flossware.hotspot.client.ClientApp
import org.flossware.hotspot.client.MainActivity
import org.flossware.hotspot.client.R
import org.flossware.hotspot.client.model.Transport
import org.flossware.hotspot.client.model.VpnState
import org.flossware.hotspot.client.tunnel.SocksTunnel
import org.flossware.hotspot.client.tunnel.SocksTunnel.Companion.DNS_ADDRESS

class TunnelService : VpnService() {

    private var tunInterface: ParcelFileDescriptor? = null
    private var socksTunnel: SocksTunnel? = null
    private var bluetoothTunnel: BluetoothTunnel? = null
    private var usbTunnel: UsbTunnel? = null
    private var scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val host = intent.getStringExtra(EXTRA_SOCKS_HOST) ?: VpnState.DEFAULT_SOCKS_HOST
                val port = intent.getIntExtra(EXTRA_SOCKS_PORT, VpnState.DEFAULT_SOCKS_PORT)
                connect(host, port)
            }
            ACTION_CONNECT_BT -> {
                val address = intent.getStringExtra(EXTRA_BT_DEVICE_ADDRESS) ?: return START_NOT_STICKY
                connectBluetooth(address)
            }
            ACTION_CONNECT_USB -> {
                val deviceName = intent.getStringExtra(EXTRA_USB_DEVICE_NAME) ?: return START_NOT_STICKY
                connectUsb(deviceName)
            }
            ACTION_DISCONNECT -> disconnect()
        }
        return START_NOT_STICKY
    }

    private fun connect(socksHost: String, socksPort: Int, transport: Transport = Transport.WIFI_DIRECT) {
        if (tunInterface != null) return

        startForeground(NOTIFICATION_ID, buildNotification())

        try {
            val builder = Builder()
                .setSession("FlossWare Tunnel")
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addAddress("fd00::2", 128)
                .addRoute("::", 0)
                .setMtu(1500)
                .addDisallowedApplication(packageName)
                .addDnsServer(DNS_ADDRESS)

            val tun = builder.establish() ?: run {
                _state.value = _state.value.copy(error = getString(R.string.error_vpn_permission_denied))
                stopSelf()
                return
            }

            tunInterface = tun

            socksTunnel = SocksTunnel(
                tunFd = tun.fd,
                socksHost = socksHost,
                socksPort = socksPort,
                cacheDir = cacheDir,
            ).also { it.start() }

            _state.value = VpnState(
                isConnected = true,
                socksHost = socksHost,
                socksPort = socksPort,
                transport = transport,
            )
            Log.i(TAG, "VPN tunnel established to $socksHost:$socksPort via $transport")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish tunnel", e)
            _state.value = _state.value.copy(
                isConnected = false,
                error = e.message ?: getString(R.string.error_generic_connection_failed),
            )
            disconnect()
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectBluetooth(deviceAddress: String) {
        if (tunInterface != null) return

        startForeground(NOTIFICATION_ID, buildNotification())

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter
        if (adapter == null) {
            _state.value = _state.value.copy(error = getString(R.string.error_bluetooth_not_available))
            stopSelf()
            return
        }

        val device = adapter.getRemoteDevice(deviceAddress)
        val btTunnel = BluetoothTunnel(
            remoteDevice = device,
            fallbackErrorMessage = getString(R.string.error_bluetooth_connection_failed),
        )
        bluetoothTunnel = btTunnel
        btTunnel.start()

        scope.launch {
            val result = btTunnel.state.first {
                it is BluetoothTunnelState.Connected || it is BluetoothTunnelState.Error
            }
            when (result) {
                is BluetoothTunnelState.Connected ->
                    connect("127.0.0.1", result.localPort, Transport.BLUETOOTH)
                is BluetoothTunnelState.Error -> {
                    _state.value = _state.value.copy(error = result.message)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                else -> {}
            }
        }
    }

    private fun connectUsb(deviceName: String) {
        if (tunInterface != null) return

        startForeground(NOTIFICATION_ID, buildNotification())

        val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usbManager == null) {
            _state.value = _state.value.copy(error = "USB not available")
            stopSelf()
            return
        }

        val device = usbManager.deviceList.values.firstOrNull { it.deviceName == deviceName }
        if (device == null) {
            _state.value = _state.value.copy(error = "USB device not found")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val tunnel = UsbTunnel(usbManager, device)
        usbTunnel = tunnel
        tunnel.start()

        scope.launch {
            val result = tunnel.state.first {
                it is UsbTunnelState.Connected || it is UsbTunnelState.Error
            }
            when (result) {
                is UsbTunnelState.Connected ->
                    connect("127.0.0.1", result.localPort, Transport.USB)
                is UsbTunnelState.Error -> {
                    _state.value = _state.value.copy(error = result.message)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                else -> {}
            }
        }
    }

    private fun disconnect() {
        if (tunInterface == null && socksTunnel == null && bluetoothTunnel == null && usbTunnel == null) {
            // Already disconnected -- avoid double-cleanup
            return
        }
        Log.i(TAG, "Disconnecting VPN tunnel")

        // 1. Cancel coroutines to stop accepting new work
        scope.cancel()
        scope = CoroutineScope(Dispatchers.Main + Job())

        // 2. Stop the native tun2socks tunnel
        socksTunnel?.stop()
        socksTunnel = null

        // 3. Stop transport tunnels
        bluetoothTunnel?.stop()
        bluetoothTunnel = null
        usbTunnel?.stop()
        usbTunnel = null

        // 4. Close VPN interface
        try {
            tunInterface?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing VPN interface: ${e.message}")
        }
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
        const val ACTION_CONNECT_BT = "org.flossware.hotspot.client.CONNECT_BT"
        const val ACTION_CONNECT_USB = "org.flossware.hotspot.client.CONNECT_USB"
        const val ACTION_DISCONNECT = "org.flossware.hotspot.client.DISCONNECT"
        const val EXTRA_SOCKS_HOST = "socks_host"
        const val EXTRA_SOCKS_PORT = "socks_port"
        const val EXTRA_BT_DEVICE_ADDRESS = "bt_device_address"
        const val EXTRA_USB_DEVICE_NAME = "usb_device_name"
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

        fun connectBluetooth(context: Context, deviceAddress: String) {
            val intent = Intent(context, TunnelService::class.java).apply {
                action = ACTION_CONNECT_BT
                putExtra(EXTRA_BT_DEVICE_ADDRESS, deviceAddress)
            }
            context.startForegroundService(intent)
        }

        fun connectUsb(context: Context, deviceName: String) {
            val intent = Intent(context, TunnelService::class.java).apply {
                action = ACTION_CONNECT_USB
                putExtra(EXTRA_USB_DEVICE_NAME, deviceName)
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
