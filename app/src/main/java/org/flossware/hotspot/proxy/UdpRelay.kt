package org.flossware.hotspot.proxy

import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * SOCKS5 UDP relay (RFC 1928 Section 7-8).
 *
 * Binds a DatagramSocket to receive SOCKS5 UDP frames from a client,
 * parses the encapsulated destination, forwards the payload, and relays
 * responses back wrapped in SOCKS5 UDP frame format.
 *
 * Each unique destination gets its own outbound DatagramSocket with a
 * configurable idle timeout.  A shared [activeMappings] counter enforces
 * a global cap across all relay instances.
 */
class UdpRelay(
    private val bindAddress: InetAddress,
    private val dnsResolver: (String) -> InetAddress = { InetAddress.getByName(it) },
    private val ssrfProtection: Boolean = true,
    private val bytesTransferred: AtomicLong = AtomicLong(0),
    private val activeMappings: AtomicInteger = AtomicInteger(0),
    private val maxMappings: Int = MAX_MAPPINGS,
    internal val mappingTimeoutMs: Long = MAPPING_TIMEOUT_MS,
    @Volatile var debugMode: Boolean = false,
) {
    @Volatile private var relaySocket: DatagramSocket? = null
    val port: Int get() = relaySocket?.localPort ?: 0

    private val running = AtomicBoolean(false)

    /** Most-recently-seen client endpoint (updated on every received frame). */
    @Volatile private var clientAddr: InetAddress? = null
    @Volatile private var clientPort: Int = 0

    /** Per-destination outbound socket mappings. */
    internal val mappings = ConcurrentHashMap<MappingKey, OutboundMapping>()

    private var receiveThread: Thread? = null
    private val responseExecutor = Executors.newCachedThreadPool { r ->
        Thread(r).apply { isDaemon = true; name = "udp-relay-resp-${System.nanoTime()}" }
    }
    private var cleanupExecutor: ScheduledExecutorService? = null

    val isRunning: Boolean get() = running.get()
    val mappingCount: Int get() = mappings.size

    fun start(): Boolean {
        if (running.getAndSet(true)) return true
        return try {
            val socket = DatagramSocket(0, bindAddress)
            socket.soTimeout = SOCKET_TIMEOUT_MS
            relaySocket = socket

            val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "udp-relay-cleanup").apply { isDaemon = true }
            }
            cleanupExecutor = scheduler
            scheduler.scheduleAtFixedRate(
                ::cleanupIdleMappings,
                mappingTimeoutMs,
                mappingTimeoutMs / 2,
                TimeUnit.MILLISECONDS,
            )

            receiveThread = Thread {
                receiveLoop(socket)
            }.apply {
                name = "udp-relay-${socket.localPort}"
                isDaemon = true
                start()
            }

            Log.i(TAG, "UDP relay on ${bindAddress.hostAddress}:${socket.localPort}")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start UDP relay", e)
            running.set(false)
            false
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return

        relaySocket?.close()
        relaySocket = null

        for ((_, mapping) in mappings) {
            mapping.socket.close()
            activeMappings.decrementAndGet()
        }
        mappings.clear()

        cleanupExecutor?.shutdownNow()
        cleanupExecutor = null
        responseExecutor.shutdownNow()

        receiveThread?.let { t ->
            try {
                t.join(SHUTDOWN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        receiveThread = null

        Log.i(TAG, "UDP relay stopped")
    }

    // ---- receive loop ----

    @Suppress("LoopWithTooManyJumpStatements")
    private fun receiveLoop(socket: DatagramSocket) {
        val buffer = ByteArray(MAX_UDP_FRAME_SIZE)
        while (running.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: SocketException) {
                break
            } catch (e: IOException) {
                if (running.get()) Log.w(TAG, "Relay receive error: ${e.message}")
                break
            }

            val data = packet.data.copyOf(packet.length)
            val senderAddr = packet.address
            val senderPort = packet.port

            clientAddr = senderAddr
            clientPort = senderPort

            handleClientFrame(data, senderAddr, senderPort)
        }
    }

    @Suppress("ReturnCount")
    internal fun handleClientFrame(
        data: ByteArray,
        senderAddr: InetAddress,
        senderPort: Int,
    ) {
        val frame = parseUdpFrame(data) ?: run {
            if (debugMode) Log.d(TAG, "Invalid UDP frame from ${senderAddr.hostAddress}:$senderPort")
            return
        }

        if (frame.frag != 0.toByte()) {
            if (debugMode) Log.d(TAG, "Rejecting fragmented UDP (FRAG=${frame.frag})")
            return
        }

        val resolved = try {
            if (frame.addrType == ADDR_DOMAIN) {
                dnsResolver(frame.dstAddr)
            } else {
                InetAddress.getByName(frame.dstAddr)
            }
        } catch (e: Exception) {
            Log.w(TAG, "DNS resolution failed for ${frame.dstAddr}: ${e.message}")
            return
        }

        if (ssrfProtection && Socks5Server.isBlockedDestination(resolved)) {
            Log.w(TAG, "SSRF blocked: UDP to ${frame.dstAddr}:${frame.dstPort}")
            return
        }

        val key = MappingKey(resolved, frame.dstPort)
        val mapping = mappings[key] ?: createMapping(key) ?: return

        mapping.lastUsed.set(System.currentTimeMillis())

        try {
            val outPacket = DatagramPacket(
                frame.payload, frame.payload.size, resolved, frame.dstPort,
            )
            mapping.socket.send(outPacket)
            bytesTransferred.addAndGet(frame.payload.size.toLong())
            if (debugMode) {
                Log.d(TAG, "Forwarded ${frame.payload.size}B to " +
                    "${resolved.hostAddress}:${frame.dstPort}")
            }
        } catch (e: IOException) {
            Log.w(TAG, "Send failed to ${resolved.hostAddress}:${frame.dstPort}: ${e.message}")
        }
    }

    private fun createMapping(key: MappingKey): OutboundMapping? {
        if (activeMappings.get() >= maxMappings) {
            Log.w(TAG, "UDP mapping pool at capacity ($maxMappings)")
            return null
        }

        return try {
            val outbound = DatagramSocket()
            outbound.soTimeout = SOCKET_TIMEOUT_MS
            val newMapping = OutboundMapping(outbound, AtomicLong(System.currentTimeMillis()))

            val existing = mappings.putIfAbsent(key, newMapping)
            if (existing != null) {
                outbound.close()
                existing
            } else {
                activeMappings.incrementAndGet()
                startResponseReceiver(newMapping)
                newMapping
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to create outbound socket: ${e.message}")
            null
        }
    }

    // ---- response relay (upstream -> client) ----

    @Suppress("LoopWithTooManyJumpStatements")
    private fun startResponseReceiver(mapping: OutboundMapping) {
        responseExecutor.execute {
            val buffer = ByteArray(MAX_UDP_FRAME_SIZE)
            while (running.get() && !mapping.socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    mapping.socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: SocketException) {
                    break
                } catch (e: IOException) {
                    if (running.get()) Log.w(TAG, "Response receive error: ${e.message}")
                    break
                }

                mapping.lastUsed.set(System.currentTimeMillis())

                val responseData = packet.data.copyOf(packet.length)
                val srcAddr = packet.address
                val srcPort = packet.port
                val frame = buildUdpFrame(srcAddr, srcPort, responseData)

                try {
                    val relay = relaySocket ?: break
                    val ca = clientAddr ?: continue
                    val cp = clientPort
                    if (cp == 0) continue
                    relay.send(DatagramPacket(frame, frame.size, ca, cp))
                    bytesTransferred.addAndGet(responseData.size.toLong())
                    if (debugMode) {
                        Log.d(TAG, "Relayed ${responseData.size}B from " +
                            "${srcAddr.hostAddress}:$srcPort")
                    }
                } catch (e: IOException) {
                    if (running.get()) Log.w(TAG, "Failed to relay response: ${e.message}")
                }
            }
        }
    }

    // ---- idle-mapping cleanup ----

    internal fun cleanupIdleMappings() {
        val now = System.currentTimeMillis()
        val iterator = mappings.entries.iterator()
        while (iterator.hasNext()) {
            val (_, mapping) = iterator.next()
            if (now - mapping.lastUsed.get() > mappingTimeoutMs) {
                iterator.remove()
                mapping.socket.close()
                activeMappings.decrementAndGet()
                if (debugMode) Log.d(TAG, "Cleaned up idle UDP mapping")
            }
        }
    }

    // ---- SOCKS5 UDP frame codec (RFC 1928 Section 8) ----

    @Suppress("ReturnCount")
    internal fun parseUdpFrame(data: ByteArray): UdpFrame? {
        if (data.size < MIN_FRAME_SIZE) return null

        val frag = data[2]
        val atyp = data[3].toInt() and BYTE_MASK
        var offset = FRAME_HEADER_SIZE

        val dstAddr: String
        val addrType: Byte

        when (atyp) {
            ADDR_IPV4.toInt() -> {
                if (data.size < offset + IPV4_LENGTH + PORT_LENGTH) return null
                val addr = ByteArray(IPV4_LENGTH)
                System.arraycopy(data, offset, addr, 0, IPV4_LENGTH)
                dstAddr = InetAddress.getByAddress(addr).hostAddress ?: "0.0.0.0"
                addrType = ADDR_IPV4
                offset += IPV4_LENGTH
            }
            ADDR_DOMAIN.toInt() -> {
                if (data.size < offset + 1) return null
                val len = data[offset].toInt() and BYTE_MASK
                offset++
                if (len == 0 || len > MAX_DOMAIN_LENGTH) return null
                if (data.size < offset + len + PORT_LENGTH) return null
                dstAddr = String(data, offset, len, Charsets.US_ASCII)
                addrType = ADDR_DOMAIN
                offset += len
            }
            ADDR_IPV6.toInt() -> {
                if (data.size < offset + IPV6_LENGTH + PORT_LENGTH) return null
                val addr = ByteArray(IPV6_LENGTH)
                System.arraycopy(data, offset, addr, 0, IPV6_LENGTH)
                dstAddr = InetAddress.getByAddress(addr).hostAddress ?: "::0"
                addrType = ADDR_IPV6
                offset += IPV6_LENGTH
            }
            else -> return null
        }

        if (data.size < offset + PORT_LENGTH) return null
        val dstPort = ((data[offset].toInt() and BYTE_MASK) shl 8) or
            (data[offset + 1].toInt() and BYTE_MASK)
        offset += PORT_LENGTH

        val payload = if (offset < data.size) {
            data.copyOfRange(offset, data.size)
        } else {
            ByteArray(0)
        }

        return UdpFrame(frag, addrType, dstAddr, dstPort, payload)
    }

    internal fun buildUdpFrame(
        srcAddr: InetAddress,
        srcPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val addrBytes = srcAddr.address
        val addrType = if (srcAddr is Inet6Address) ADDR_IPV6 else ADDR_IPV4

        val frameSize = FRAME_HEADER_SIZE + addrBytes.size + PORT_LENGTH + payload.size
        val frame = ByteArray(frameSize)

        // RSV + FRAG + ATYP
        frame[0] = 0x00
        frame[1] = 0x00
        frame[2] = 0x00
        frame[3] = addrType

        System.arraycopy(addrBytes, 0, frame, FRAME_HEADER_SIZE, addrBytes.size)
        val portOffset = FRAME_HEADER_SIZE + addrBytes.size
        frame[portOffset] = ((srcPort shr 8) and BYTE_MASK).toByte()
        frame[portOffset + 1] = (srcPort and BYTE_MASK).toByte()
        System.arraycopy(payload, 0, frame, portOffset + PORT_LENGTH, payload.size)

        return frame
    }

    // ---- data types ----

    data class MappingKey(val address: InetAddress, val port: Int)

    class OutboundMapping(
        val socket: DatagramSocket,
        val lastUsed: AtomicLong,
    )

    data class UdpFrame(
        val frag: Byte,
        val addrType: Byte,
        val dstAddr: String,
        val dstPort: Int,
        val payload: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UdpFrame) return false
            return frag == other.frag && addrType == other.addrType &&
                dstAddr == other.dstAddr && dstPort == other.dstPort &&
                payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = frag.hashCode()
            result = 31 * result + addrType.hashCode()
            result = 31 * result + dstAddr.hashCode()
            result = 31 * result + dstPort
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    internal companion object {
        private const val TAG = "UdpRelay"
        const val MAX_MAPPINGS = 1000
        const val MAPPING_TIMEOUT_MS = 60_000L
        const val MAX_UDP_FRAME_SIZE = 65535
        private const val SHUTDOWN_TIMEOUT_MS = 3000L
        private const val SOCKET_TIMEOUT_MS = 1000
        private const val MIN_FRAME_SIZE = 4
        private const val FRAME_HEADER_SIZE = 4
        private const val PORT_LENGTH = 2
        private const val IPV4_LENGTH = 4
        private const val IPV6_LENGTH = 16
        private const val MAX_DOMAIN_LENGTH = 253
        private const val BYTE_MASK = 0xFF
        private const val ADDR_IPV4: Byte = 0x01
        private const val ADDR_DOMAIN: Byte = 0x03
        private const val ADDR_IPV6: Byte = 0x04
    }
}
