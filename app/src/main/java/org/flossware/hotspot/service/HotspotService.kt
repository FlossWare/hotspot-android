package org.flossware.hotspot.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.app.Service
import android.os.PowerManager
import android.os.SystemClock
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
import org.flossware.hotspot.R
import org.flossware.hotspot.model.HotspotState
import java.net.InetAddress

class HotspotService : Service() {

    private var scope = CoroutineScope(Dispatchers.Main + Job())
    private val wifiDirectManager = WifiDirectManager()
    private lateinit var networkManager: NetworkManager
    private val proxyManager = ProxyManager()
    private val bluetoothManager = BluetoothManager()
    private val usbServer = UsbServer()
    private lateinit var notificationHelper: NotificationHelper
    private var wakeLock: PowerManager.WakeLock? = null
    private var startTimeElapsed: Long = 0L
    private var lastBytesTransferred: Long = 0L
    private var idlePolls: Int = 0

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
            ACTION_TOGGLE_BT -> toggleBluetooth(intent.getBooleanExtra(EXTRA_BT_ENABLED, false))
            ACTION_START_BT_ONLY -> startBluetoothOnly()
        }
        return START_NOT_STICKY
    }

    private fun startHotspot() {
        Log.i(TAG, "Starting hotspot service")
        scope.cancel()
        scope = CoroutineScope(Dispatchers.Main + Job())
        networkManager.onNetworkLost = {
            _state.value = _state.value.copy(error = getString(R.string.error_mobile_data_lost))
            proxyManager.notifyNetworkLost()
        }
        networkManager.unregister()
        startForeground(NOTIFICATION_ID, notificationHelper.build(0))
        networkManager.register()
        acquireWakeLock()
        startTimeElapsed = SystemClock.elapsedRealtime()
        lastBytesTransferred = 0L
        idlePolls = 0

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
                val currentBytes = proxyManager.bytesTransferred
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
                if (!proxyManager.isRunning) continue
                val current = _state.value
                if (current.isRunning) {
                    val uptimeMs = SystemClock.elapsedRealtime() - startTimeElapsed
                    _state.value = current.copy(
                        bytesTransferred = proxyManager.bytesTransferred,
                        dnsCacheHits = proxyManager.dnsCacheHits,
                        httpCacheHits = proxyManager.httpCacheHits,
                        dataSaved = proxyManager.dataSaved,
                        uptimeSeconds = uptimeMs / 1000,
                        isIdle = idlePolls >= IDLE_THRESHOLD_POLLS,
                    )
                    wifiDirectManager.refreshPeers()
                }
            }
        }

        val passphrase = getPassphrase(this)
        wifiDirectManager.start(this, passphrase)
    }

    /**
     * Starts the hotspot in Bluetooth-only mode when Wi-Fi Direct is unavailable.
     * Binds the SOCKS5 proxy to the loopback address and starts Bluetooth transport.
     */
    private fun startBluetoothOnly() {
        Log.i(TAG, "Starting hotspot in Bluetooth-only mode")
        scope.cancel()
        scope = CoroutineScope(Dispatchers.Main + Job())
        networkManager.onNetworkLost = {
            _state.value = _state.value.copy(error = getString(R.string.error_mobile_data_lost))
            proxyManager.notifyNetworkLost()
        }
        networkManager.unregister()
        startForeground(NOTIFICATION_ID, notificationHelper.build(0))
        networkManager.register()
        acquireWakeLock()
        startTimeElapsed = SystemClock.elapsedRealtime()
        lastBytesTransferred = 0L
        idlePolls = 0

        val bindAddr = InetAddress.getByName("127.0.0.1")
        proxyManager.start(
            bindAddress = bindAddr,
            socketFactoryProvider = { networkManager.socketFactory },
            upstreamDnsProvider = { networkManager.upstreamDns ?: InetAddress.getByName("8.8.8.8") },
            socketBinder = { sock -> networkManager.bindSocket(sock) },
        )

        _state.value = _state.value.copy(
            bluetoothOptIn = true,
            bluetoothOnlyMode = true,
        )
        if (hasBluetoothPermissions()) {
            bluetoothManager.start(this, scope)
        }

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

        _state.value = _state.value.copy(
            isRunning = true,
            socksHost = "127.0.0.1",
        )

        scope.launch {
            while (true) {
                val currentBytes = proxyManager.bytesTransferred
                val isIdle = currentBytes == lastBytesTransferred && currentBytes > 0L
                if (isIdle) idlePolls++ else idlePolls = 0
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
                if (!proxyManager.isRunning) continue
                val current = _state.value
                if (current.isRunning) {
                    val uptimeMs = SystemClock.elapsedRealtime() - startTimeElapsed
                    _state.value = current.copy(
                        bytesTransferred = proxyManager.bytesTransferred,
                        dnsCacheHits = proxyManager.dnsCacheHits,
                        httpCacheHits = proxyManager.httpCacheHits,
                        dataSaved = proxyManager.dataSaved,
                        uptimeSeconds = uptimeMs / 1000,
                        isIdle = idlePolls >= IDLE_THRESHOLD_POLLS,
                    )
                }
            }
        }
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

        val btOptIn = getBluetoothOptIn(this)
        _state.value = _state.value.copy(bluetoothOptIn = btOptIn)
        if (btOptIn && hasBluetoothPermissions()) {
            bluetoothManager.start(this, scope)
        }

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

        usbServer.start(this)

        scope.launch {
            usbServer.state.collect { usbState ->
                _state.value = _state.value.copy(
                    usbConnected = usbState is UsbState.Connected,
                )
            }
        }
    }

    private fun updateState(wifiState: WifiDirectState.GroupCreated) {
        val current = _state.value
        _state.value = HotspotState(
            isRunning = true,
            networkName = wifiState.networkName,
            passphrase = wifiState.passphrase,
            configuredPassphrase = current.configuredPassphrase,
            socksHost = wifiState.groupOwnerAddress,
            connectedDevices = wifiState.connectedDevices,
            bytesTransferred = proxyManager.bytesTransferred,
            bluetoothOptIn = current.bluetoothOptIn,
            bluetoothEnabled = current.bluetoothEnabled,
            bluetoothDeviceName = current.bluetoothDeviceName,
            bluetoothConnectedDevices = current.bluetoothConnectedDevices,
            usbConnected = current.usbConnected,
        )
        notificationHelper.update(wifiState.connectedDevices.size)
    }

    private fun toggleBluetooth(enabled: Boolean) {
        _state.value = _state.value.copy(bluetoothOptIn = enabled)
        if (!_state.value.isRunning) return
        if (enabled && hasBluetoothPermissions()) {
            bluetoothManager.start(this, scope)
        } else {
            bluetoothManager.stop()
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "FlossHotspot::ServiceWakeLock",
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

    private fun stopHotspot() {
        if (!_state.value.isRunning && !proxyManager.isRunning) {
            // Already stopped or never started -- avoid double-cleanup
            return
        }
        Log.i(TAG, "Stopping hotspot service")

        // 1. Cancel coroutines to stop accepting new work
        scope.cancel()

        // 2. Stop transport servers (stop accepting new connections)
        usbServer.stop()
        usbServer.unregisterReceiver(this)
        bluetoothManager.stop()

        // 3. Stop proxy (closes active connections and executors)
        proxyManager.stop()

        // 4. Remove Wi-Fi Direct group
        wifiDirectManager.stop()

        // 5. Release system resources
        networkManager.unregister()
        releaseWakeLock()

        _state.value = HotspotState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopHotspot()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HotspotService"
        private const val PREFS_NAME = "hotspot_prefs"
        private const val KEY_BT_OPT_IN = "bluetooth_opt_in"
        private const val KEY_PASSPHRASE = "wifi_direct_passphrase"
        private const val KEY_PAIRING_REQUIRED = "pairing_required"
        const val ACTION_START = "org.flossware.hotspot.START"
        const val ACTION_STOP = "org.flossware.hotspot.STOP"
        const val ACTION_TOGGLE_BT = "org.flossware.hotspot.TOGGLE_BT"
        const val ACTION_START_BT_ONLY = "org.flossware.hotspot.START_BT_ONLY"
        const val EXTRA_BT_ENABLED = "bt_enabled"
        const val CHANNEL_ID = "hotspot_service"
        const val NOTIFICATION_ID = 1
        internal const val ACTIVE_POLL_MS = 2000L
        internal const val IDLE_POLL_MS = 10000L
        internal const val IDLE_THRESHOLD_POLLS = 15
        private const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L

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

        fun setBluetoothOptIn(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_BT_OPT_IN, enabled)
                .apply()

            if (_state.value.isRunning) {
                val intent = Intent(context, HotspotService::class.java).apply {
                    action = ACTION_TOGGLE_BT
                    putExtra(EXTRA_BT_ENABLED, enabled)
                }
                context.startService(intent)
            } else {
                _state.value = _state.value.copy(bluetoothOptIn = enabled)
            }
        }

        fun getBluetoothOptIn(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_BT_OPT_IN, false)
        }

        /**
         * Sets the Wi-Fi Direct passphrase. Only takes effect before starting the hotspot.
         * The passphrase must be at least [WifiDirectManager.MIN_PASSPHRASE_LENGTH] characters.
         */
        fun setPassphrase(context: Context, passphrase: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PASSPHRASE, passphrase)
                .apply()
            _state.value = _state.value.copy(configuredPassphrase = passphrase)
        }

        /**
         * Returns the configured passphrase, generating a random one on first launch.
         */
        fun getPassphrase(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            var passphrase = prefs.getString(KEY_PASSPHRASE, null)
            if (passphrase == null) {
                passphrase = WifiDirectManager.generateRandomPassphrase()
                prefs.edit().putString(KEY_PASSPHRASE, passphrase).apply()
            }
            return passphrase
        }

        fun updateFeatureAvailability(
            wifiDirectAvailable: Boolean,
            bluetoothAvailable: Boolean,
            usbAvailable: Boolean,
            mobileDataAvailable: Boolean,
        ) {
            _state.value = _state.value.copy(
                wifiDirectAvailable = wifiDirectAvailable,
                bluetoothAvailable = bluetoothAvailable,
                usbAvailable = usbAvailable,
                mobileDataAvailable = mobileDataAvailable,
            )
        }

        fun updatePermissionsDenied(denied: Boolean) {
            _state.value = _state.value.copy(permissionsDenied = denied)
        }

        fun startBluetoothOnly(context: Context) {
            val intent = Intent(context, HotspotService::class.java).apply {
                action = ACTION_START_BT_ONLY
            }
            context.startForegroundService(intent)
        }

        /**
         * Sets whether incoming connections require pairing (key-based auth).
         * Default is false for backward compatibility.
         */
        fun setPairingRequired(context: Context, required: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PAIRING_REQUIRED, required)
                .apply()
            _state.value = _state.value.copy(pairingRequired = required)
        }

        fun getPairingRequired(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_PAIRING_REQUIRED, false)
        }

        fun updatePairingState(fingerprint: String, pairedCount: Int) {
            _state.value = _state.value.copy(
                pairingFingerprint = fingerprint,
                pairedDeviceCount = pairedCount,
            )
        }
    }
}
