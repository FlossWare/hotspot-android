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
import org.flossware.hotspot.R
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.flossware.hotspot.model.ConnectedDevice
import java.security.SecureRandom

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
    private var retryCount = 0
    private var retryHandler: android.os.Handler? = null
    private var configuredPassphrase: String = DEFAULT_PASSPHRASE

    private val _state = MutableStateFlow<WifiDirectState>(WifiDirectState.Idle)
    val state: StateFlow<WifiDirectState> = _state.asStateFlow()

    @SuppressLint("MissingPermission")
    fun start(ctx: Context, passphrase: String = DEFAULT_PASSPHRASE) {
        configuredPassphrase = passphrase
        context = ctx
        retryHandler = android.os.Handler(Looper.getMainLooper())

        // Check hardware support before attempting to initialize
        if (!ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WIFI_DIRECT)) {
            _state.value = WifiDirectState.Error(ctx.getString(R.string.error_no_wifi_direct))
            return
        }

        manager = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (manager == null) {
            _state.value = WifiDirectState.Error(ctx.getString(R.string.error_wifi_direct_not_supported))
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
                            _state.value = WifiDirectState.Error(context.getString(R.string.error_wifi_direct_disabled))
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
        retryHandler?.removeCallbacksAndMessages(null)
        retryHandler = null
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
        retryCount = 0
        createGroupWithRetry()
    }

    @SuppressLint("MissingPermission")
    private fun createGroupWithRetry() {
        val ch = channel ?: return
        val mgr = manager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val config = WifiP2pConfig.Builder()
                .setNetworkName("DIRECT-FW-FlossHotspot")
                .setPassphrase(configuredPassphrase)
                .build()
            mgr.createGroup(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "Group created (custom config)")
                    retryCount = 0
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
                retryCount = 0
                requestGroupInfo()
            }
            override fun onFailure(reason: Int) {
                if (retryCount < MAX_RETRIES && reason != WifiP2pManager.P2P_UNSUPPORTED) {
                    retryCount++
                    val delayMs = RETRY_DELAYS_MS.getOrElse(retryCount - 1) { RETRY_DELAYS_MS.last() }
                    Log.w(TAG, "Group creation failed (reason=$reason), retry $retryCount/$MAX_RETRIES in ${delayMs}ms")
                    retryHandler?.postDelayed({
                        createGroupWithRetry()
                    }, delayMs)
                    return
                }
                _state.value = WifiDirectState.Error(mapFailureReason(reason))
            }
        })
    }

    private fun mapFailureReason(reason: Int): String {
        val ctx = context ?: return Companion.mapFailureReason(reason)
        return when (reason) {
            WifiP2pManager.ERROR -> ctx.getString(R.string.error_wifi_direct_generic)
            WifiP2pManager.P2P_UNSUPPORTED -> ctx.getString(R.string.error_no_wifi_direct)
            WifiP2pManager.BUSY -> ctx.getString(R.string.error_wifi_direct_busy)
            WifiP2pManager.NO_SERVICE_REQUESTS -> ctx.getString(R.string.error_service_discovery_failed)
            else -> ctx.getString(R.string.error_hotspot_creation_failed, reason)
        }
    }

    @SuppressLint("MissingPermission")
    private fun removeGroup() {
        val ch = channel ?: return
        val mgr = manager ?: return
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Wi-Fi Direct group removed")
            }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "Failed to remove Wi-Fi Direct group (reason=$reason)")
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun requestGroupInfo() {
        val ch = channel ?: return
        val mgr = manager ?: return

        mgr.requestGroupInfo(ch) { group: WifiP2pGroup? ->
            if (group != null && group.isGroupOwner) {
                val unknownName = context?.getString(R.string.unknown_device) ?: "Unknown"
                val devices = group.clientList.map { device ->
                    ConnectedDevice(
                        macAddress = device.deviceAddress,
                        deviceName = device.deviceName.ifEmpty { unknownName },
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
        internal const val MAX_RETRIES = 2
        internal const val MIN_PASSPHRASE_LENGTH = 8
        private val RETRY_DELAYS_MS = longArrayOf(1000L, 2000L)
        private const val RANDOM_PASSPHRASE_LENGTH = 12
        private const val PASSPHRASE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"

        /** Default passphrase used when none is configured. */
        const val DEFAULT_PASSPHRASE = "FlossWare2024"

        /**
         * Generates a cryptographically random passphrase.
         * Uses characters that avoid ambiguity (no 0/O, 1/l/I).
         */
        fun generateRandomPassphrase(): String {
            val random = SecureRandom()
            return (1..RANDOM_PASSPHRASE_LENGTH)
                .map { PASSPHRASE_CHARS[random.nextInt(PASSPHRASE_CHARS.length)] }
                .joinToString("")
        }

        internal fun mapFailureReason(reason: Int): String = when (reason) {
            WifiP2pManager.ERROR ->
                "Wi-Fi Direct failed. Some devices require Wi-Fi to be ON. " +
                "Try enabling Wi-Fi and Location services, then retry."
            WifiP2pManager.P2P_UNSUPPORTED ->
                "Wi-Fi Direct is not supported on this device."
            WifiP2pManager.BUSY ->
                "Wi-Fi Direct is busy. Please wait a moment and try again."
            WifiP2pManager.NO_SERVICE_REQUESTS ->
                "Service discovery failed. Try toggling Wi-Fi off and on."
            else ->
                "Failed to create hotspot (error code: $reason). " +
                "Try restarting Wi-Fi or rebooting your device."
        }
    }
}
