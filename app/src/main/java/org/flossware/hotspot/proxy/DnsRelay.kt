package org.flossware.hotspot.proxy

import timber.log.Timber
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
                Timber.tag(TAG).i("dns_start event=dns_relay_listen port=%d", listenPort)

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
                        Timber.tag(TAG).d("dns_query event=dns_query client=%s port=%d size=%d",
                            clientAddr.hostAddress, clientPort, queryData.size)
                    }

                    queryExecutor.execute {
                        forwardQuery(sock, queryData, clientAddr, clientPort)
                    }
                }
            } catch (e: IOException) {
                if (running.get()) {
                    Timber.tag(TAG).e(e, "DNS relay error")
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
        thread?.let { t ->
            t.interrupt()
            try {
                t.join(SHUTDOWN_TIMEOUT_MS)
                if (t.isAlive) {
                    Timber.tag(TAG).w("DNS relay thread did not terminate within %dms", SHUTDOWN_TIMEOUT_MS)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        thread = null
        queryExecutor.shutdownNow()
        try {
            if (!queryExecutor.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Timber.tag(TAG).w("Query executor did not terminate within %dms", SHUTDOWN_TIMEOUT_MS)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        cache.clear()
        Timber.tag(TAG).i("dns_stop event=dns_relay_stopped hits=%d misses=%d", _cacheHits.get(), _cacheMisses.get())
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
                if (debugMode) {
                    Timber.tag(TAG).d(
                        "dns_query event=cache_hit client=%s port=%d",
                        clientAddr.hostAddress, clientPort,
                    )
                }
                val response = patchTransactionId(cached.responseData, queryData)
                val replyPacket = DatagramPacket(response, response.size, clientAddr, clientPort)
                synchronized(listenSocket) {
                    listenSocket.send(replyPacket)
                }
                return
            }
        }

        _cacheMisses.incrementAndGet()
        if (debugMode) {
            Timber.tag(TAG).d(
                "dns_query event=cache_miss client=%s port=%d",
                clientAddr.hostAddress, clientPort,
            )
        }

        var upstream: DatagramSocket? = null
        try {
            upstream = DatagramSocket()
            upstream.soTimeout = 5000

            socketBinder(upstream)

            val dnsServer = upstreamDnsProvider()
            if (debugMode) {
                Timber.tag(TAG).d(
                    "dns_query event=forward server=%s port=%d",
                    dnsServer.hostAddress, upstreamPort,
                )
            }
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
                Timber.tag(TAG).w(
                    "DNS transaction ID mismatch for %s:%d, dropping response",
                    clientAddr.hostAddress, clientPort,
                )
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

            // Happy Eyeballs v2 (RFC 8305): when an A query completes,
            // prefetch the AAAA record in the background so it is cached
            // when the client asks for it.
            val qtype = extractQueryType(queryData)
            if (qtype == QTYPE_A) {
                val aaaaQuery = buildAlternateTypeQuery(queryData, QTYPE_AAAA)
                if (aaaaQuery != null) {
                    val aaaaKey = extractQuestionKey(aaaaQuery)
                    if (aaaaKey != null && cache[aaaaKey] == null) {
                        queryExecutor.execute {
                            prefetchQuery(aaaaQuery, dnsServer)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Timber.tag(TAG).w("DNS forward failed for %s:%d: %s", clientAddr.hostAddress, clientPort, e.message)
        } finally {
            upstream?.close()
        }
    }

    /**
     * Prefetches a DNS query in the background and caches the result.
     * Used for Happy Eyeballs AAAA prefetching when an A query is seen.
     */
    private fun prefetchQuery(queryData: ByteArray, dnsServer: InetAddress) {
        var upstream: DatagramSocket? = null
        try {
            upstream = DatagramSocket()
            upstream.soTimeout = PREFETCH_TIMEOUT_MS

            socketBinder(upstream)

            val queryPacket = DatagramPacket(queryData, queryData.size, dnsServer, upstreamPort)
            upstream.send(queryPacket)

            val responseBuffer = ByteArray(4096)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            upstream.receive(responsePacket)

            val responseData = responsePacket.data.copyOf(responsePacket.length)
            if (responseData.size >= 2 && queryData.size >= 2 &&
                responseData[0] == queryData[0] && responseData[1] == queryData[1]
            ) {
                val cacheKey = extractQuestionKey(queryData)
                if (cacheKey != null) {
                    val ttl = extractMinTtl(responseData)
                    if (ttl > 0) {
                        evictIfNeeded()
                        cache[cacheKey] = CachedDnsResponse(
                            responseData = responseData,
                            expiresAt = System.currentTimeMillis() + ttl * 1000L,
                        )
                        if (debugMode) Timber.tag(TAG).d("Prefetched AAAA record")
                    }
                }
            }
        } catch (e: IOException) {
            if (debugMode) Timber.tag(TAG).d("AAAA prefetch failed: %s", e.message)
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

    /**
     * Extracts the QTYPE from a DNS query packet.
     * Returns the 16-bit query type (e.g. 1 for A, 28 for AAAA), or null if parsing fails.
     */
    internal fun extractQueryType(queryData: ByteArray): Int? {
        if (queryData.size < 12) return null
        val qdCount = ((queryData[4].toInt() and 0xFF) shl 8) or (queryData[5].toInt() and 0xFF)
        if (qdCount == 0) return null
        // Walk past the domain name labels to reach QTYPE
        var pos = 12
        while (pos < queryData.size) {
            val len = queryData[pos].toInt() and 0xFF
            if (len == 0) {
                pos++ // null terminator
                break
            }
            pos += 1 + len
        }
        if (pos + 2 > queryData.size) return null
        return ((queryData[pos].toInt() and 0xFF) shl 8) or (queryData[pos + 1].toInt() and 0xFF)
    }

    /**
     * Creates a copy of the DNS query with a different QTYPE.
     * Used for Happy Eyeballs v2: when we see an A query, we create an AAAA query
     * (and vice versa) to prefetch the alternate record type.
     */
    internal fun buildAlternateTypeQuery(queryData: ByteArray, newQtype: Int): ByteArray? {
        if (queryData.size < 12) return null
        val qdCount = ((queryData[4].toInt() and 0xFF) shl 8) or (queryData[5].toInt() and 0xFF)
        if (qdCount == 0) return null
        // Find the QTYPE offset by walking past domain name labels
        var pos = 12
        while (pos < queryData.size) {
            val len = queryData[pos].toInt() and 0xFF
            if (len == 0) {
                pos++ // null terminator
                break
            }
            pos += 1 + len
        }
        if (pos + 2 > queryData.size) return null
        val result = queryData.copyOf()
        result[pos] = ((newQtype shr 8) and 0xFF).toByte()
        result[pos + 1] = (newQtype and 0xFF).toByte()
        return result
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
        private const val SHUTDOWN_TIMEOUT_MS = 3000L
        private const val PREFETCH_TIMEOUT_MS = 3000

        /** DNS query type for A (IPv4 address) records. */
        internal const val QTYPE_A = 1
        /** DNS query type for AAAA (IPv6 address) records. */
        internal const val QTYPE_AAAA = 28
    }
}
