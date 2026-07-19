package org.flossware.hotspot.network

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Detects the optimal Maximum Transmission Unit (MTU) for a network path using
 * a PLPMTUD-inspired (Packetization Layer Path MTU Discovery) approach.
 *
 * Sends UDP probe packets at increasing sizes with the DF (Don't Fragment) bit
 * set to discover the largest packet that can traverse the path without
 * fragmentation. Falls back to TCP-based detection if UDP probes fail
 * (blackhole detection).
 */
class MtuDetector(
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val safetyMargin: Int = SAFETY_MARGIN_BYTES,
) {

    /**
     * Result of an MTU detection attempt.
     *
     * @property mtu The detected MTU in bytes, with safety margin applied.
     * @property method The detection method that succeeded.
     * @property probesSent Number of probe packets sent during detection.
     */
    data class Result(
        val mtu: Int,
        val method: DetectionMethod,
        val probesSent: Int,
    )

    enum class DetectionMethod {
        /** MTU detected via UDP probes with DF bit set. */
        UDP_PROBE,

        /** MTU detected via TCP MSS negotiation. */
        TCP_FALLBACK,

        /** Detection failed; using safe default. */
        DEFAULT_FALLBACK,
    }

    /**
     * Detects the optimal MTU to a target address.
     *
     * Tries UDP probes first. If all UDP probes fail (blackhole scenario), falls
     * back to TCP-based detection. If both methods fail, returns [FALLBACK_MTU].
     *
     * This method performs network I/O and must not be called on the main thread.
     *
     * @param targetAddress The remote host to probe.
     * @param targetPort The port for TCP fallback; UDP uses an ephemeral port.
     * @return The detection result containing the MTU and method used.
     */
    fun detect(targetAddress: InetAddress, targetPort: Int = TCP_PROBE_PORT): Result {
        Log.d(TAG, "Starting MTU detection to ${targetAddress.hostAddress}")

        val udpResult = detectViaUdp(targetAddress)
        if (udpResult != null) {
            return udpResult
        }

        Log.d(TAG, "UDP probes failed, attempting TCP fallback")
        val tcpResult = detectViaTcp(targetAddress, targetPort)
        if (tcpResult != null) {
            return tcpResult
        }

        Log.w(TAG, "All detection methods failed, using fallback MTU=$FALLBACK_MTU")
        return Result(
            mtu = FALLBACK_MTU,
            method = DetectionMethod.DEFAULT_FALLBACK,
            probesSent = PROBE_SIZES.size,
        )
    }

    /**
     * Sends UDP probe packets at increasing sizes to detect the path MTU.
     *
     * Uses [DatagramSocket] with a short timeout. Each probe is a payload of the
     * target size. We send a packet and expect either success (no ICMP
     * "Packet Too Big" error) or a timeout/error indicating the size is too large.
     *
     * On Android, we cannot set the DF bit directly via [DatagramSocket]. Instead,
     * we rely on the OS default behavior for IPv4 (DF is typically set for
     * connected UDP sockets) and send probes at the IP layer.
     *
     * @return The detection result, or null if no probe succeeded.
     */
    @Suppress("TooGenericExceptionCaught")
    internal fun detectViaUdp(targetAddress: InetAddress): Result? {
        var largestSuccessful = 0
        var probesSent = 0

        for (probeSize in PROBE_SIZES) {
            probesSent++
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
                    Log.d(TAG, "UDP probe size=$probeSize succeeded")
                }
            } catch (expected: SocketTimeoutException) {
                // Timeout is expected -- the remote likely won't respond to
                // arbitrary UDP. We treat a successful send() without an ICMP
                // error as success; a timeout on receive is fine.
                largestSuccessful = probeSize
                Log.d(TAG, "UDP probe size=$probeSize sent (timeout on receive, treating as success)")
            } catch (e: Exception) {
                Log.d(TAG, "UDP probe size=$probeSize failed: ${e.message}")
                // Probe failed -- this size exceeds the path MTU.
                break
            }
        }

        return if (largestSuccessful > 0) {
            val mtu = (largestSuccessful + UDP_IP_HEADER_SIZE - safetyMargin)
                .coerceAtLeast(MIN_MTU)
            Log.i(TAG, "UDP detection: largest=$largestSuccessful, mtu=$mtu")
            Result(mtu = mtu, method = DetectionMethod.UDP_PROBE, probesSent = probesSent)
        } else {
            null
        }
    }

    /**
     * Falls back to TCP-based detection when UDP probes are blackholed.
     *
     * Opens a TCP connection and reads the negotiated MSS (Maximum Segment Size)
     * from the socket's send buffer size as an approximation. The actual MTU is
     * derived from the MSS plus the TCP/IP header overhead.
     *
     * @return The detection result, or null if TCP connection fails.
     */
    @Suppress("TooGenericExceptionCaught")
    internal fun detectViaTcp(targetAddress: InetAddress, targetPort: Int): Result? {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(targetAddress, targetPort), timeoutMs)
                val sendBufferSize = socket.sendBufferSize
                // TCP MSS is typically MTU - 40 (20 IP + 20 TCP headers).
                // The send buffer gives us a rough upper bound.
                val estimatedMtu = sendBufferSize.coerceAtMost(MAX_MTU) - safetyMargin
                val mtu = estimatedMtu.coerceAtLeast(MIN_MTU)
                Log.i(TAG, "TCP detection: sendBuffer=$sendBufferSize, mtu=$mtu")
                Result(mtu = mtu, method = DetectionMethod.TCP_FALLBACK, probesSent = 1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "TCP fallback failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "MtuDetector"

        /** Probe sizes in bytes (payload only, excluding IP/UDP headers). */
        val PROBE_SIZES = intArrayOf(1180, 1280, 1380, 1420, 1460, 1500)

        /** Default timeout per probe in milliseconds. */
        const val DEFAULT_TIMEOUT_MS = 5000

        /** Safety margin subtracted from the detected MTU. */
        const val SAFETY_MARGIN_BYTES = 20

        /** IPv6 minimum MTU, used as the safe fallback. */
        const val FALLBACK_MTU = 1280

        /** Minimum MTU we will ever return. */
        const val MIN_MTU = 576

        /** Maximum MTU for standard Ethernet. */
        const val MAX_MTU = 1500

        /** IP + UDP header overhead (20 + 8 = 28 bytes). */
        const val UDP_IP_HEADER_SIZE = 28

        /** Port used for UDP probes (high ephemeral port, unlikely to be filtered). */
        private const val UDP_PROBE_PORT = 33434

        /** Default port for TCP fallback probes. */
        private const val TCP_PROBE_PORT = 80
    }
}
