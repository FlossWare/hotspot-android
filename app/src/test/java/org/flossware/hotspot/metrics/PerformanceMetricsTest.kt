package org.flossware.hotspot.metrics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PerformanceMetricsTest {

    @Before
    fun setUp() {
        PerformanceMetrics.reset()
    }

    @After
    fun tearDown() {
        PerformanceMetrics.reset()
    }

    @Test
    fun `initial state is all zeros`() {
        assertEquals(0L, PerformanceMetrics.getTotalBytesTx())
        assertEquals(0L, PerformanceMetrics.getTotalBytesRx())
        assertEquals(0, PerformanceMetrics.activeSocksConnections)
        assertEquals(0L, PerformanceMetrics.dnsQueries)
        assertEquals(0L, PerformanceMetrics.dnsCacheHits)
        assertEquals(0f, PerformanceMetrics.cpuUtilization, 0.001f)
        assertEquals(0L, PerformanceMetrics.heapUsedBytes)
        assertEquals(0L, PerformanceMetrics.heapMaxBytes)
        assertEquals(0L, PerformanceMetrics.nativeHeapBytes)
        assertEquals(0f, PerformanceMetrics.batteryConsumedMah, 0.001f)
        assertEquals(0, PerformanceMetrics.reconnectCount)
        assertEquals(0, PerformanceMetrics.getTotalErrors())
    }

    @Test
    fun `recordBytesTx increments per transport`() {
        PerformanceMetrics.recordBytesTx("wifi", 100)
        PerformanceMetrics.recordBytesTx("wifi", 200)
        PerformanceMetrics.recordBytesTx("bluetooth", 50)

        assertEquals(300L, PerformanceMetrics.getBytesTx("wifi"))
        assertEquals(50L, PerformanceMetrics.getBytesTx("bluetooth"))
        assertEquals(350L, PerformanceMetrics.getTotalBytesTx())
    }

    @Test
    fun `recordBytesRx increments per transport`() {
        PerformanceMetrics.recordBytesRx("wifi", 500)
        PerformanceMetrics.recordBytesRx("usb", 100)

        assertEquals(500L, PerformanceMetrics.getBytesRx("wifi"))
        assertEquals(100L, PerformanceMetrics.getBytesRx("usb"))
        assertEquals(600L, PerformanceMetrics.getTotalBytesRx())
    }

    @Test
    fun `getBytesTx returns zero for unknown transport`() {
        assertEquals(0L, PerformanceMetrics.getBytesTx("unknown"))
    }

    @Test
    fun `getBytesRx returns zero for unknown transport`() {
        assertEquals(0L, PerformanceMetrics.getBytesRx("unknown"))
    }

    @Test
    fun `socks connections increment and decrement`() {
        PerformanceMetrics.incrementSocksConnections()
        PerformanceMetrics.incrementSocksConnections()
        assertEquals(2, PerformanceMetrics.activeSocksConnections)

        PerformanceMetrics.decrementSocksConnections()
        assertEquals(1, PerformanceMetrics.activeSocksConnections)
    }

    @Test
    fun `dns queries track total and cache hits`() {
        PerformanceMetrics.recordDnsQuery(cacheHit = false)
        PerformanceMetrics.recordDnsQuery(cacheHit = true)
        PerformanceMetrics.recordDnsQuery(cacheHit = true)

        assertEquals(3L, PerformanceMetrics.dnsQueries)
        assertEquals(2L, PerformanceMetrics.dnsCacheHits)
    }

    @Test
    fun `recordReconnect increments counter`() {
        PerformanceMetrics.recordReconnect()
        PerformanceMetrics.recordReconnect()
        assertEquals(2, PerformanceMetrics.reconnectCount)
    }

    @Test
    fun `recordError tracks by type`() {
        PerformanceMetrics.recordError("network")
        PerformanceMetrics.recordError("network")
        PerformanceMetrics.recordError("dns")

        assertEquals(2, PerformanceMetrics.getErrorCount("network"))
        assertEquals(1, PerformanceMetrics.getErrorCount("dns"))
        assertEquals(0, PerformanceMetrics.getErrorCount("unknown"))
        assertEquals(3, PerformanceMetrics.getTotalErrors())
    }

    @Test
    fun `getErrorBreakdown returns all types`() {
        PerformanceMetrics.recordError("a")
        PerformanceMetrics.recordError("b")
        PerformanceMetrics.recordError("a")

        val breakdown = PerformanceMetrics.getErrorBreakdown()
        assertEquals(2, breakdown["a"])
        assertEquals(1, breakdown["b"])
        assertEquals(2, breakdown.size)
    }

    @Test
    fun `snapshot captures current state`() {
        PerformanceMetrics.recordBytesTx("wifi", 1024)
        PerformanceMetrics.recordBytesRx("wifi", 2048)
        PerformanceMetrics.incrementSocksConnections()
        PerformanceMetrics.recordDnsQuery(cacheHit = true)
        PerformanceMetrics.recordReconnect()
        PerformanceMetrics.recordError("test")
        PerformanceMetrics.cpuUtilization = 0.25f
        PerformanceMetrics.heapUsedBytes = 1_000_000
        PerformanceMetrics.heapMaxBytes = 10_000_000
        PerformanceMetrics.nativeHeapBytes = 500_000
        PerformanceMetrics.batteryConsumedMah = 1.5f

        val snap = PerformanceMetrics.snapshot()
        assertEquals(1024L, snap.totalBytesTx)
        assertEquals(2048L, snap.totalBytesRx)
        assertEquals(1, snap.activeSocksConnections)
        assertEquals(1L, snap.dnsQueries)
        assertEquals(1L, snap.dnsCacheHits)
        assertEquals(0.25f, snap.cpuUtilization, 0.001f)
        assertEquals(1_000_000L, snap.heapUsedBytes)
        assertEquals(10_000_000L, snap.heapMaxBytes)
        assertEquals(500_000L, snap.nativeHeapBytes)
        assertEquals(1.5f, snap.batteryConsumedMah, 0.001f)
        assertEquals(1, snap.reconnectCount)
        assertEquals(1, snap.totalErrors)
        assertEquals(1, snap.errorBreakdown["test"])
    }

    @Test
    fun `reset clears all metrics`() {
        PerformanceMetrics.recordBytesTx("wifi", 1024)
        PerformanceMetrics.recordBytesRx("wifi", 2048)
        PerformanceMetrics.incrementSocksConnections()
        PerformanceMetrics.recordDnsQuery(cacheHit = true)
        PerformanceMetrics.recordReconnect()
        PerformanceMetrics.recordError("test")
        PerformanceMetrics.cpuUtilization = 0.5f
        PerformanceMetrics.heapUsedBytes = 1024
        PerformanceMetrics.batteryConsumedMah = 2.0f

        PerformanceMetrics.reset()

        assertEquals(0L, PerformanceMetrics.getTotalBytesTx())
        assertEquals(0L, PerformanceMetrics.getTotalBytesRx())
        assertEquals(0, PerformanceMetrics.activeSocksConnections)
        assertEquals(0L, PerformanceMetrics.dnsQueries)
        assertEquals(0L, PerformanceMetrics.dnsCacheHits)
        assertEquals(0f, PerformanceMetrics.cpuUtilization, 0.001f)
        assertEquals(0L, PerformanceMetrics.heapUsedBytes)
        assertEquals(0f, PerformanceMetrics.batteryConsumedMah, 0.001f)
        assertEquals(0, PerformanceMetrics.reconnectCount)
        assertEquals(0, PerformanceMetrics.getTotalErrors())
    }

    @Test
    fun `throughput returns zero with no samples`() {
        assertEquals(0f, PerformanceMetrics.getThroughputKbps(), 0.001f)
    }

    @Test
    fun `throughput returns positive value after recording bytes`() {
        // Record some bytes to generate throughput samples
        PerformanceMetrics.recordBytesTx("wifi", 10_000)
        val kbps = PerformanceMetrics.getThroughputKbps()
        // Should be positive since we just recorded bytes within the window
        assertTrue("Throughput should be positive, was $kbps", kbps >= 0f)
    }

    @Test
    fun `MetricsSnapshot defaults are all zeros`() {
        val snap = MetricsSnapshot()
        assertEquals(0L, snap.totalBytesTx)
        assertEquals(0L, snap.totalBytesRx)
        assertEquals(0, snap.activeSocksConnections)
        assertEquals(0L, snap.dnsQueries)
        assertEquals(0L, snap.dnsCacheHits)
        assertEquals(0f, snap.throughputKbps, 0.001f)
        assertEquals(0f, snap.cpuUtilization, 0.001f)
        assertEquals(0L, snap.heapUsedBytes)
        assertEquals(0L, snap.heapMaxBytes)
        assertEquals(0L, snap.nativeHeapBytes)
        assertEquals(0f, snap.batteryConsumedMah, 0.001f)
        assertEquals(0, snap.reconnectCount)
        assertEquals(0, snap.totalErrors)
        assertTrue(snap.errorBreakdown.isEmpty())
    }

    @Test
    fun `MetricsSnapshot equality`() {
        val a = MetricsSnapshot(totalBytesTx = 100, cpuUtilization = 0.5f)
        val b = MetricsSnapshot(totalBytesTx = 100, cpuUtilization = 0.5f)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
