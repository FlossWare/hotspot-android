package org.flossware.hotspot.metrics

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe singleton that tracks runtime performance metrics for the hotspot service.
 *
 * Categories:
 *  - Network: bytes TX/RX per transport, active SOCKS5 connections, DNS queries/cache hits,
 *    throughput (kbps rolling 10s window)
 *  - System: CPU utilization, memory usage, battery consumption
 *  - Session: uptime, reconnect count, error count by type
 *
 * All counters use [AtomicLong]/[AtomicInteger] for lock-free thread safety.
 */
object PerformanceMetrics {

    // --- Network metrics ---

    private val bytesTxByTransport = ConcurrentHashMap<String, AtomicLong>()
    private val bytesRxByTransport = ConcurrentHashMap<String, AtomicLong>()
    private val _activeSocksConnections = AtomicInteger(0)
    private val _dnsQueries = AtomicLong(0)
    private val _dnsCacheHits = AtomicLong(0)

    /** Rolling throughput samples: list of (timestampMs, bytesDelta) for the last 10 seconds. */
    private val throughputSamples = mutableListOf<Pair<Long, Long>>()
    private val throughputLock = Any()

    // --- System metrics (written by MetricsCollector, read by UI) ---

    @Volatile var cpuUtilization: Float = 0f
        internal set

    @Volatile var heapUsedBytes: Long = 0L
        internal set

    @Volatile var heapMaxBytes: Long = 0L
        internal set

    @Volatile var nativeHeapBytes: Long = 0L
        internal set

    @Volatile var batteryConsumedMah: Float = 0f
        internal set

    // --- Session metrics ---

    private val _reconnectCount = AtomicInteger(0)
    private val errorCounts = ConcurrentHashMap<String, AtomicInteger>()

    // ===== Network accessors =====

    fun recordBytesTx(transport: String, bytes: Long) {
        bytesTxByTransport.computeIfAbsent(transport) { AtomicLong(0) }.addAndGet(bytes)
        recordThroughputSample(bytes)
    }

    fun recordBytesRx(transport: String, bytes: Long) {
        bytesRxByTransport.computeIfAbsent(transport) { AtomicLong(0) }.addAndGet(bytes)
        recordThroughputSample(bytes)
    }

    fun getBytesTx(transport: String): Long =
        bytesTxByTransport[transport]?.get() ?: 0L

    fun getBytesRx(transport: String): Long =
        bytesRxByTransport[transport]?.get() ?: 0L

    fun getTotalBytesTx(): Long =
        bytesTxByTransport.values.sumOf { it.get() }

    fun getTotalBytesRx(): Long =
        bytesRxByTransport.values.sumOf { it.get() }

    val activeSocksConnections: Int get() = _activeSocksConnections.get()

    fun incrementSocksConnections() {
        _activeSocksConnections.incrementAndGet()
    }

    fun decrementSocksConnections() {
        _activeSocksConnections.decrementAndGet()
    }

    fun recordDnsQuery(cacheHit: Boolean) {
        _dnsQueries.incrementAndGet()
        if (cacheHit) _dnsCacheHits.incrementAndGet()
    }

    val dnsQueries: Long get() = _dnsQueries.get()
    val dnsCacheHits: Long get() = _dnsCacheHits.get()

    /**
     * Returns the rolling throughput in kbps over the last [THROUGHPUT_WINDOW_MS] milliseconds.
     */
    fun getThroughputKbps(): Float {
        val now = System.currentTimeMillis()
        synchronized(throughputLock) {
            pruneOldSamples(now)
            val totalBytes = throughputSamples.sumOf { it.second }
            val windowSeconds = THROUGHPUT_WINDOW_MS / 1000.0
            return ((totalBytes * 8) / windowSeconds / 1000.0).toFloat()
        }
    }

    // ===== Session accessors =====

    val reconnectCount: Int get() = _reconnectCount.get()

    fun recordReconnect() {
        _reconnectCount.incrementAndGet()
    }

    fun recordError(errorType: String) {
        errorCounts.computeIfAbsent(errorType) { AtomicInteger(0) }.incrementAndGet()
    }

    fun getErrorCount(errorType: String): Int =
        errorCounts[errorType]?.get() ?: 0

    fun getTotalErrors(): Int =
        errorCounts.values.sumOf { it.get() }

    fun getErrorBreakdown(): Map<String, Int> =
        errorCounts.mapValues { it.value.get() }

    /**
     * Returns a snapshot of all metrics as a [MetricsSnapshot] data class.
     */
    fun snapshot(): MetricsSnapshot = MetricsSnapshot(
        totalBytesTx = getTotalBytesTx(),
        totalBytesRx = getTotalBytesRx(),
        activeSocksConnections = activeSocksConnections,
        dnsQueries = dnsQueries,
        dnsCacheHits = dnsCacheHits,
        throughputKbps = getThroughputKbps(),
        cpuUtilization = cpuUtilization,
        heapUsedBytes = heapUsedBytes,
        heapMaxBytes = heapMaxBytes,
        nativeHeapBytes = nativeHeapBytes,
        batteryConsumedMah = batteryConsumedMah,
        reconnectCount = reconnectCount,
        totalErrors = getTotalErrors(),
        errorBreakdown = getErrorBreakdown(),
    )

    /**
     * Resets all metrics to zero. Call when the service stops.
     */
    fun reset() {
        bytesTxByTransport.clear()
        bytesRxByTransport.clear()
        _activeSocksConnections.set(0)
        _dnsQueries.set(0)
        _dnsCacheHits.set(0)
        synchronized(throughputLock) {
            throughputSamples.clear()
        }
        cpuUtilization = 0f
        heapUsedBytes = 0L
        heapMaxBytes = 0L
        nativeHeapBytes = 0L
        batteryConsumedMah = 0f
        _reconnectCount.set(0)
        errorCounts.clear()
    }

    // ===== Internal =====

    private fun recordThroughputSample(bytes: Long) {
        val now = System.currentTimeMillis()
        synchronized(throughputLock) {
            throughputSamples.add(now to bytes)
            pruneOldSamples(now)
        }
    }

    private fun pruneOldSamples(now: Long) {
        val cutoff = now - THROUGHPUT_WINDOW_MS
        throughputSamples.removeAll { it.first < cutoff }
    }

    internal const val THROUGHPUT_WINDOW_MS = 10_000L
}

/**
 * Immutable snapshot of all performance metrics at a point in time.
 */
data class MetricsSnapshot(
    val totalBytesTx: Long = 0L,
    val totalBytesRx: Long = 0L,
    val activeSocksConnections: Int = 0,
    val dnsQueries: Long = 0L,
    val dnsCacheHits: Long = 0L,
    val throughputKbps: Float = 0f,
    val cpuUtilization: Float = 0f,
    val heapUsedBytes: Long = 0L,
    val heapMaxBytes: Long = 0L,
    val nativeHeapBytes: Long = 0L,
    val batteryConsumedMah: Float = 0f,
    val reconnectCount: Int = 0,
    val totalErrors: Int = 0,
    val errorBreakdown: Map<String, Int> = emptyMap(),
)
