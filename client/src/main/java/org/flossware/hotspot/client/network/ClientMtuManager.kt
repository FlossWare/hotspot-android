package org.flossware.hotspot.client.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.flossware.hotspot.client.model.Transport
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Manages MTU detection and caching for the VPN client. Provides the optimal MTU
 * for [android.net.VpnService.Builder.setMtu] and computes TCP MSS clamping
 * values that account for transport-specific encapsulation overhead.
 *
 * Uses PLPMTUD-inspired detection: sends UDP probe packets at increasing sizes
 * to discover the path MTU, with TCP-based fallback for blackhole scenarios.
 * Results are cached per (transport, peer) pair and reused on reconnect.
 *
 * Thread safety: All public methods are safe to call from any thread. The cache
 * is synchronized and the detection logic is stateless.
 */
class ClientMtuManager(
    context: Context,
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE)

    private val cacheLock = Any()

    /**
     * MTU information for a VPN tunnel.
     *
     * @property mtu The effective MTU for the tunnel interface.
     * @property tcpMssV4 TCP Maximum Segment Size for IPv4 (MTU - 40).
     * @property tcpMssV6 TCP Maximum Segment Size for IPv6 (MTU - 60).
     * @property fromCache True if the MTU was retrieved from cache rather than detected.
     * @property detectionMethod The method used to determine the MTU, or null if cached.
     */
    data class MtuInfo(
        val mtu: Int,
        val tcpMssV4: Int,
        val tcpMssV6: Int,
        val fromCache: Boolean,
        val detectionMethod: DetectionMethod?,
    )

    enum class DetectionMethod {
        UDP_PROBE,
        TCP_FALLBACK,
        DEFAULT_FALLBACK,
    }

    /**
     * Returns the optimal MTU for the given transport and peer.
     *
     * First checks the cache. If no cached value exists, runs detection against
     * the target address, subtracts the transport encapsulation overhead, and
     * caches the result.
     *
     * This method performs network I/O when the cache misses and must not be
     * called on the main thread.
     *
     * @param transport The transport type in use.
     * @param peerIdentifier A stable identifier for the peer (e.g. MAC address or IP).
     * @param targetAddress The remote address to probe for MTU detection.
     * @return MTU information including MSS clamping values.
     */
    fun getMtu(
        transport: Transport,
        peerIdentifier: String,
        targetAddress: InetAddress,
    ): MtuInfo {
        val transportKey = transport.name.lowercase()

        val cachedMtu = getCached(transportKey, peerIdentifier)
        if (cachedMtu != null) {
            Log.d(TAG, "Using cached MTU=$cachedMtu for $transportKey/$peerIdentifier")
            return buildMtuInfo(cachedMtu, fromCache = true, method = null)
        }

        val (rawMtu, method) = detectMtu(targetAddress)
        val overhead = encapsulationOverhead(transport)
        val effectiveMtu = (rawMtu - overhead).coerceAtLeast(MIN_TUNNEL_MTU)

        putCached(transportKey, peerIdentifier, effectiveMtu)
        Log.i(
            TAG,
            "Detected MTU=$effectiveMtu for $transportKey/$peerIdentifier " +
                "(raw=$rawMtu, overhead=$overhead, method=$method)",
        )

        return buildMtuInfo(effectiveMtu, fromCache = false, method = method)
    }

    /**
     * Invalidates the cached MTU for a transport/peer pair, forcing re-detection
     * on the next call to [getMtu].
     */
    fun invalidate(transport: Transport, peerIdentifier: String) {
        val key = cacheKey(transport.name.lowercase(), peerIdentifier)
        synchronized(cacheLock) {
            prefs.edit()
                .remove(mtuPrefKey(key))
                .remove(timestampPrefKey(key))
                .apply()
        }
    }

    // -- MTU detection --

    /**
     * Detects the path MTU to the target address. Tries UDP probes first, then
     * TCP fallback, then returns a safe default.
     */
    @Suppress("TooGenericExceptionCaught")
    internal fun detectMtu(targetAddress: InetAddress): Pair<Int, DetectionMethod> {
        val udpMtu = detectViaUdp(targetAddress)
        if (udpMtu != null) return udpMtu

        Log.d(TAG, "UDP probes failed, attempting TCP fallback")
        val tcpMtu = detectViaTcp(targetAddress)
        if (tcpMtu != null) return tcpMtu

        Log.w(TAG, "All detection failed, using fallback MTU=$FALLBACK_MTU")
        return FALLBACK_MTU to DetectionMethod.DEFAULT_FALLBACK
    }

    @Suppress("TooGenericExceptionCaught")
    private fun detectViaUdp(targetAddress: InetAddress): Pair<Int, DetectionMethod>? {
        var largestSuccessful = 0

        for (probeSize in PROBE_SIZES) {
            try {
                DatagramSocket().use { socket ->
                    socket.soTimeout = timeoutMs
                    socket.connect(targetAddress, UDP_PROBE_PORT)

                    val payload = ByteArray(probeSize)
                    val packet = DatagramPacket(
                        payload,
                        payload.size,
                        targetAddress,
                        UDP_PROBE_PORT,
                    )
                    socket.send(packet)
                    largestSuccessful = probeSize
                }
            } catch (expected: SocketTimeoutException) {
                largestSuccessful = probeSize
            } catch (e: Exception) {
                Log.d(TAG, "UDP probe size=$probeSize failed: ${e.message}")
                break
            }
        }

        return if (largestSuccessful > 0) {
            val mtu = (largestSuccessful + UDP_IP_HEADER_SIZE - SAFETY_MARGIN)
                .coerceAtLeast(MIN_MTU)
            mtu to DetectionMethod.UDP_PROBE
        } else {
            null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun detectViaTcp(targetAddress: InetAddress): Pair<Int, DetectionMethod>? {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(targetAddress, TCP_PROBE_PORT), timeoutMs)
                val sendBufferSize = socket.sendBufferSize
                val mtu = sendBufferSize.coerceAtMost(MAX_MTU) - SAFETY_MARGIN
                mtu.coerceAtLeast(MIN_MTU) to DetectionMethod.TCP_FALLBACK
            }
        } catch (e: Exception) {
            Log.w(TAG, "TCP fallback failed: ${e.message}")
            null
        }
    }

    // -- Cache --

    private fun getCached(transportType: String, peerIdentifier: String): Int? =
        synchronized(cacheLock) {
            val key = cacheKey(transportType, peerIdentifier)
            val mtu = prefs.getInt(mtuPrefKey(key), 0)
            if (mtu == 0) return null

            val timestamp = prefs.getLong(timestampPrefKey(key), 0L)
            if (System.currentTimeMillis() - timestamp > CACHE_MAX_AGE_MS) {
                prefs.edit()
                    .remove(mtuPrefKey(key))
                    .remove(timestampPrefKey(key))
                    .apply()
                return null
            }
            mtu
        }

    private fun putCached(transportType: String, peerIdentifier: String, mtu: Int) =
        synchronized(cacheLock) {
            val key = cacheKey(transportType, peerIdentifier)
            prefs.edit()
                .putInt(mtuPrefKey(key), mtu)
                .putLong(timestampPrefKey(key), System.currentTimeMillis())
                .apply()
        }

    private fun buildMtuInfo(
        mtu: Int,
        fromCache: Boolean,
        method: DetectionMethod?,
    ): MtuInfo {
        return MtuInfo(
            mtu = mtu,
            tcpMssV4 = (mtu - TCP_IPV4_HEADER_SIZE).coerceAtLeast(0),
            tcpMssV6 = (mtu - TCP_IPV6_HEADER_SIZE).coerceAtLeast(0),
            fromCache = fromCache,
            detectionMethod = method,
        )
    }

    companion object {
        private const val TAG = "ClientMtuManager"
        internal const val CACHE_PREFS_NAME = "client_mtu_cache"

        /** Probe sizes in bytes (payload only, excluding IP/UDP headers). */
        val PROBE_SIZES = intArrayOf(1180, 1280, 1380, 1420, 1460, 1500)

        const val DEFAULT_TIMEOUT_MS = 5000
        const val SAFETY_MARGIN = 20
        const val FALLBACK_MTU = 1280
        const val MIN_MTU = 576
        const val MAX_MTU = 1500
        const val UDP_IP_HEADER_SIZE = 28
        const val MIN_TUNNEL_MTU = 1000

        /** IPv4 TCP/IP header overhead: 20 (IP) + 20 (TCP) = 40 bytes. */
        const val TCP_IPV4_HEADER_SIZE = 40

        /** IPv6 TCP/IP header overhead: 40 (IP) + 20 (TCP) = 60 bytes. */
        const val TCP_IPV6_HEADER_SIZE = 60

        /** Wi-Fi Direct encapsulation overhead in bytes. */
        const val WIFI_DIRECT_OVERHEAD = 80

        /** Bluetooth encapsulation overhead in bytes. */
        const val BLUETOOTH_OVERHEAD = 50

        /** USB tethering encapsulation overhead in bytes. */
        const val USB_OVERHEAD = 60

        /** Cache entries expire after 30 days. */
        const val CACHE_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

        private const val UDP_PROBE_PORT = 33434
        private const val TCP_PROBE_PORT = 80

        /**
         * Returns the encapsulation overhead in bytes for the given transport.
         */
        fun encapsulationOverhead(transport: Transport): Int = when (transport) {
            Transport.WIFI_DIRECT -> WIFI_DIRECT_OVERHEAD
            Transport.BLUETOOTH -> BLUETOOTH_OVERHEAD
            Transport.USB -> USB_OVERHEAD
        }

        private fun cacheKey(transportType: String, peerIdentifier: String) =
            "$transportType:$peerIdentifier"

        private fun mtuPrefKey(key: String) = "mtu:$key"
        private fun timestampPrefKey(key: String) = "ts:$key"
    }
}
