package org.flossware.hotspot.client.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

sealed class WifiConnectionState {
    data object Idle : WifiConnectionState()
    data object Connecting : WifiConnectionState()
    data class Connected(val network: Network) : WifiConnectionState()
    data class Error(val message: String) : WifiConnectionState()
    data object ManualRequired : WifiConnectionState()
}

class WifiConnector {
    private val _state = MutableStateFlow<WifiConnectionState>(WifiConnectionState.Idle)
    val state: StateFlow<WifiConnectionState> = _state.asStateFlow()

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val timeoutHandler = Handler(Looper.getMainLooper())

    fun connect(context: Context, networkName: String, passphrase: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectApi29(context, networkName, passphrase)
        } else {
            _state.value = WifiConnectionState.ManualRequired
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun connectApi29(context: Context, networkName: String, passphrase: String) {
        _state.value = WifiConnectionState.Connecting

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(networkName)
            .setWpa2Passphrase(passphrase)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = cm

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                timeoutHandler.removeCallbacksAndMessages(null)
                Timber.tag(TAG).i("wifi_connect event=connected ssid=%s", networkName)
                _state.value = WifiConnectionState.Connected(network)
            }

            override fun onUnavailable() {
                timeoutHandler.removeCallbacksAndMessages(null)
                Timber.tag(TAG).w("wifi_connect event=unavailable ssid=%s", networkName)
                _state.value = WifiConnectionState.Error(
                    "Failed to connect to $networkName",
                )
            }
        }
        networkCallback = callback

        try {
            cm.requestNetwork(request, callback)
            Timber.tag(TAG).i("wifi_connect event=requesting ssid=%s", networkName)

            timeoutHandler.postDelayed({
                if (_state.value is WifiConnectionState.Connecting) {
                    Timber.tag(TAG).w("wifi_connect event=timeout ssid=%s", networkName)
                    try {
                        cm.unregisterNetworkCallback(callback)
                    } catch (e: IllegalArgumentException) {
                        Timber.tag(TAG).w(e, "wifi_connect event=timeout_unregister_failed")
                    }
                    _state.value = WifiConnectionState.Error(
                        "Connection timed out — check the network name and password, " +
                            "or look for a system Wi-Fi dialog to approve",
                    )
                }
            }, CONNECT_TIMEOUT_MS)
        } catch (e: SecurityException) {
            Timber.tag(TAG).e(e, "wifi_connect event=security_error ssid=%s", networkName)
            _state.value = WifiConnectionState.Error(e.message ?: "Permission denied")
        }
    }

    fun disconnect() {
        timeoutHandler.removeCallbacksAndMessages(null)
        networkCallback?.let { callback ->
            try {
                connectivityManager?.unregisterNetworkCallback(callback)
            } catch (e: IllegalArgumentException) {
                Timber.tag(TAG).w(e, "wifi_disconnect event=callback_not_registered")
            }
        }
        networkCallback = null
        connectivityManager = null
        _state.value = WifiConnectionState.Idle
    }

    companion object {
        private const val TAG = "WifiConnector"
        private const val CONNECT_TIMEOUT_MS = 30_000L
    }
}
