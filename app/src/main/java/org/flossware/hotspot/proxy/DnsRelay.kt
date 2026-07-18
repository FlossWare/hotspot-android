package org.flossware.hotspot.proxy

import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class DnsRelay(
    private val bindAddress: InetAddress,
    private val listenPort: Int = 5353,
    private val upstreamDnsProvider: () -> InetAddress,
    private val upstreamPort: Int = 53,
    private val socketBinder: (DatagramSocket) -> Unit = {},
    @Volatile var debugMode: Boolean = false,
) {
    @Volatile private var socket: DatagramSocket? = null
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    private val cache = ConcurrentHashMap<ByteArrayKey, CachedDnsResponse>()
    private val queryExecutor = ThreadPoolExecutor(
        4, 8, 60L, TimeUnit.SECONDS, LinkedBlockingQueue(32),
        ThreadPoolExecutor.CallerRunsPolicy(),
    )
    private val _cacheHits = AtomicLong(0)
    private val _cacheMisses = AtomicLong(0)

    val isRunning: Boolean get() = running.get()
    val cacheHits: Long get() = _cacheHits.get()
    val cacheMisses: Long get() = _cacheMisses.get()

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread {
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket(listenPort, bindAddress)
                sock.soTimeout = 1000
                socket = sock
                Log.i(TAG, "DNS relay listening on $bindAddress:$listenPort")

                val buffer = ByteArray(4096)
                while (running.get()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        sock.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }

                    val clientAddr = packet.address
                    val clientPort = packet.port
                    val queryData = packet.data.copyOf(packet.length)

                    if (debugMode) {
                        Log.d(TAG, "DNS query from ${clientAddr.hostAddress}:$clientPort (${queryData.size}B)")
                    }

                    queryExecutor.execute {
                        forwardQuery(sock, queryData, clientAddr, clientPort)
                    }
                }
            } catch (e: IOException) {
                if (running.get()) {
                    Log.e(TAG, "DNS relay error", e)
                }
            } finally {
                sock?.close()
                socket = null
            }
        }.apply {
            name = "dns-relay"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        socket?.close()
        socket = null
        thread?.interrupt()
        thread = null
        queryExecutor.shutdownNow()
        cache.clear()
        Log.i(TAG, "DNS relay stopped (cache: ${_cacheHits.get()} hits, ${_cacheMisses.get()} misses)")
    }

    internal fun forwardQuery(
        listenSocket: DatagramSocket,
        queryData: ByteArray,
        clientAddr: InetAddress,
        clientPort: Int,
    ) {
        val cacheKey = extractQuestionKey(queryData)

        if (cacheKey != null) {
            val cached = cache[cacheKey]
            if (cached != null && !cached.isExpired()) {
                _cacheHits.incrementAndGet()
                if (debugMode) Log.d(TAG, "DNS cache hit for ${clientAddr.hostAddress}:$clientPort")
                val response = patchTransactionId(cached.responseData, queryData)
                val replyPacket = DatagramPacket(response, response.size, clientAddr, clientPort)
                synchronized(listenSocket) {
                    listenSocket.send(replyPacket)
                }
                return
            }
        }

        _cacheMisses.incrementAndGet()
        if (debugMode) Log.d(TAG, "DNS cache miss for ${clientAddr.hostAddress}:$clientPort")

        var upstream: DatagramSocket? = null
        try {
            upstream = DatagramSocket()
            upstream.soTimeout = 5000

            socketBinder(upstream)

            val dnsServer = upstreamDnsProvider()
            if (debugMode) Log.d(TAG, "Forwarding DNS query to ${dnsServer.hostAddress}:$upstreamPort")
            val queryPacket = DatagramPacket(queryData, queryData.size, dnsServer, upstreamPort)
            upstream.send(queryPacket)

            val responseBuffer = ByteArray(4096)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            upstream.receive(responsePacket)

            val responseData = responsePacket.data.copyOf(responsePacket.length)

            // Validate transaction ID: first 2 bytes of response must match the query
            if (responseData.size < 2 || queryData.size < 2 ||
                responseData[0] != queryData[0] || responseData[1] != queryData[1]
            ) {
                Log.w(TAG, "DNS transaction ID mismatch for ${clientAddr.hostAddress}:$clientPort, dropping response")
                return
            }

            if (cacheKey != null) {
                val ttl = extractMinTtl(responseData)
                if (ttl > 0) {
                    evictIfNeeded()
                    cache[cacheKey] = CachedDnsResponse(
                        responseData = responseData,
                        expiresAt = System.currentTimeMillis() + ttl * 1000L,
                    )
                }
            }

            val replyPacket = DatagramPacket(
                responseData,
                responseData.size,
                clientAddr,
                clientPort,
            )
            synchronized(listenSocket) {
                listenSocket.send(replyPacket)
            }
        } catch (e: IOException) {
            Log.w(TAG, "DNS forward failed for ${clientAddr.hostAddress}:$clientPort: ${e.message}")
        } finally {
            upstream?.close()
        }
    }

    internal fun extractQuestionKey(queryData: ByteArray): ByteArrayKey? {
        if (queryData.size < 12) return null
        val qdCount = ((queryData[4].toInt() and 0xFF) shl 8) or (queryData[5].toInt() and 0xFF)
        if (qdCount == 0) return null
        // Key = bytes from offset 2 (skip transaction ID) through the question section
        // We include flags + question to distinguish queries
        val questionEnd = findQuestionEnd(queryData, 12) ?: return null
        return ByteArrayKey(queryData.copyOfRange(2, questionEnd))
    }

    private fun findQuestionEnd(data: ByteArray, offset: Int): Int? {
        var pos = offset
        while (pos < data.size) {
            val len = data[pos].toInt() and 0xFF
            if (len == 0) {
                pos++ // null terminator
                pos += 4 // QTYPE (2) + QCLASS (2)
                return if (pos <= data.size) pos else null
            }
            pos += 1 + len
        }
        return null
    }

    private fun patchTransactionId(cached: ByteArray, query: ByteArray): ByteArray {
        val patched = cached.copyOf()
        if (patched.size >= 2 && query.size >= 2) {
            patched[0] = query[0]
            patched[1] = query[1]
        }
        return patched
    }

    internal fun extractMinTtl(responseData: ByteArray): Int {
        if (responseData.size < 12) return DEFAULT_TTL
        val anCount = ((responseData[6].toInt() and 0xFF) shl 8) or (responseData[7].toInt() and 0xFF)
        if (anCount == 0) return DEFAULT_TTL

        var pos = findQuestionEnd(responseData, 12) ?: return DEFAULT_TTL
        var minTtl = Int.MAX_VALUE

        for (i in 0 until anCount) {
            if (pos >= responseData.size) break
            // Skip name (may be compressed pointer)
            pos = skipName(responseData, pos) ?: break
            if (pos + 10 > responseData.size) break
            // Skip TYPE (2) + CLASS (2)
            pos += 4
            val ttl = ((responseData[pos].toInt() and 0xFF) shl 24) or
                ((responseData[pos + 1].toInt() and 0xFF) shl 16) or
                ((responseData[pos + 2].toInt() and 0xFF) shl 8) or
                (responseData[pos + 3].toInt() and 0xFF)
            if (ttl in 1 until minTtl) minTtl = ttl
            pos += 4
            val rdLength = ((responseData[pos].toInt() and 0xFF) shl 8) or
                (responseData[pos + 1].toInt() and 0xFF)
            pos += 2 + rdLength
        }

        return if (minTtl == Int.MAX_VALUE) DEFAULT_TTL else minTtl.coerceIn(MIN_TTL, MAX_TTL)
    }

    private fun skipName(data: ByteArray, offset: Int): Int? {
        var pos = offset
        while (pos < data.size) {
            val b = data[pos].toInt() and 0xFF
            if (b == 0) return pos + 1
            if (b and 0xC0 == 0xC0) return pos + 2 // compression pointer
            pos += 1 + b
        }
        return null
    }

    private fun evictIfNeeded() {
        if (cache.size >= MAX_CACHE_SIZE) {
            cache.entries.removeAll { it.value.isExpired() }
            if (cache.size >= MAX_CACHE_SIZE) {
                val oldest = cache.entries.minByOrNull { it.value.expiresAt }
                if (oldest != null) cache.remove(oldest.key)
            }
        }
    }

    internal data class CachedDnsResponse(
        val responseData: ByteArray,
        val expiresAt: Long,
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() >= expiresAt

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CachedDnsResponse) return false
            return responseData.contentEquals(other.responseData) && expiresAt == other.expiresAt
        }

        override fun hashCode(): Int = responseData.contentHashCode() * 31 + expiresAt.hashCode()
    }

    internal class ByteArrayKey(private val data: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ByteArrayKey) return false
            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int = data.contentHashCode()
    }

    companion object {
        private const val TAG = "DnsRelay"
        internal const val MAX_CACHE_SIZE = 1000
        internal const val DEFAULT_TTL = 60
        internal const val MIN_TTL = 10
        internal const val MAX_TTL = 3600
    }
}
