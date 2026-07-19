package org.flossware.hotspot.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import timber.log.Timber
import java.net.DatagramSocket
import java.net.InetAddress
import javax.net.SocketFactory

/**
 * Manages mobile network registration, upstream DNS detection, and socket binding.
 *
 * Encapsulates ConnectivityManager callbacks so that HotspotService does not
 * deal with network plumbing directly.
 */
class NetworkManager(private val context: Context) {

    @Volatile var network: Network? = null
        private set

    @Volatile var upstreamDns: InetAddress? = null
        private set

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Callback invoked on the ConnectivityManager thread when mobile data is lost. */
    var onNetworkLost: (() -> Unit)? = null

    /** Returns the mobile-network socket factory, or null when no mobile network is available. */
    val socketFactory: SocketFactory?
        get() = network?.socketFactory

    /** Binds a UDP socket to the mobile network (no-op when no network is available). */
    fun bindSocket(socket: DatagramSocket) {
        network?.bindSocket(socket)
    }

    /** Registers a cellular-network callback with the system ConnectivityManager. */
    fun register() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                this@NetworkManager.network = network
                Timber.tag(TAG).i("Mobile network available")
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                if (network == this@NetworkManager.network) {
                    upstreamDns = lp.dnsServers.firstOrNull()
                }
            }

            override fun onLost(network: Network) {
                if (network == this@NetworkManager.network) {
                    this@NetworkManager.network = null
                    onNetworkLost?.invoke()
                }
            }
        }.also {
            cm.registerNetworkCallback(request, it)
        }
    }

    /** Unregisters the previously registered network callback (safe to call if none registered). */
    fun unregister() {
        networkCallback?.let { cb ->
            try {
                context.getSystemService(ConnectivityManager::class.java)
                    .unregisterNetworkCallback(cb)
            } catch (_: Exception) {
            }
        }
        networkCallback = null
        network = null
        upstreamDns = null
    }

    companion object {
        private const val TAG = "NetworkManager"
    }
}
