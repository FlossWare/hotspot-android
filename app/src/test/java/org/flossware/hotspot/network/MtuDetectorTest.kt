package org.flossware.hotspot.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MtuDetectorTest {

    @Test
    fun `default timeout is 5000ms`() {
        assertEquals(5000, MtuDetector.DEFAULT_TIMEOUT_MS)
    }

    @Test
    fun `safety margin is 20 bytes`() {
        assertEquals(20, MtuDetector.SAFETY_MARGIN_BYTES)
    }

    @Test
    fun `fallback MTU is 1280`() {
        assertEquals(1280, MtuDetector.FALLBACK_MTU)
    }

    @Test
    fun `minimum MTU is 576`() {
        assertEquals(576, MtuDetector.MIN_MTU)
    }

    @Test
    fun `maximum MTU is 1500`() {
        assertEquals(1500, MtuDetector.MAX_MTU)
    }

    @Test
    fun `UDP IP header size is 28 bytes`() {
        assertEquals(28, MtuDetector.UDP_IP_HEADER_SIZE)
    }

    @Test
    fun `probe sizes are in ascending order`() {
        val sizes = MtuDetector.PROBE_SIZES
        for (i in 1 until sizes.size) {
            assertTrue(
                "Probe size ${sizes[i]} should be greater than ${sizes[i - 1]}",
                sizes[i] > sizes[i - 1],
            )
        }
    }

    @Test
    fun `probe sizes contain expected values`() {
        val sizes = MtuDetector.PROBE_SIZES.toList()
        assertTrue(sizes.contains(1180))
        assertTrue(sizes.contains(1280))
        assertTrue(sizes.contains(1380))
        assertTrue(sizes.contains(1420))
        assertTrue(sizes.contains(1460))
        assertTrue(sizes.contains(1500))
    }

    @Test
    fun `probe sizes has 6 entries`() {
        assertEquals(6, MtuDetector.PROBE_SIZES.size)
    }

    @Test
    fun `result data class holds correct values`() {
        val result = MtuDetector.Result(
            mtu = 1400,
            method = MtuDetector.DetectionMethod.UDP_PROBE,
            probesSent = 4,
        )
        assertEquals(1400, result.mtu)
        assertEquals(MtuDetector.DetectionMethod.UDP_PROBE, result.method)
        assertEquals(4, result.probesSent)
    }

    @Test
    fun `result with tcp fallback method`() {
        val result = MtuDetector.Result(
            mtu = 1460,
            method = MtuDetector.DetectionMethod.TCP_FALLBACK,
            probesSent = 1,
        )
        assertEquals(MtuDetector.DetectionMethod.TCP_FALLBACK, result.method)
    }

    @Test
    fun `result with default fallback method`() {
        val result = MtuDetector.Result(
            mtu = MtuDetector.FALLBACK_MTU,
            method = MtuDetector.DetectionMethod.DEFAULT_FALLBACK,
            probesSent = 6,
        )
        assertEquals(MtuDetector.DetectionMethod.DEFAULT_FALLBACK, result.method)
        assertEquals(MtuDetector.FALLBACK_MTU, result.mtu)
    }

    @Test
    fun `constructor accepts custom timeout`() {
        val detector = MtuDetector(timeoutMs = 3000)
        assertNotNull(detector)
    }

    @Test
    fun `constructor accepts custom safety margin`() {
        val detector = MtuDetector(safetyMargin = 30)
        assertNotNull(detector)
    }

    @Test
    fun `detection method enum has three values`() {
        val methods = MtuDetector.DetectionMethod.entries
        assertEquals(3, methods.size)
    }

    @Test
    fun `result equality works`() {
        val a = MtuDetector.Result(1400, MtuDetector.DetectionMethod.UDP_PROBE, 4)
        val b = MtuDetector.Result(1400, MtuDetector.DetectionMethod.UDP_PROBE, 4)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `result copy works`() {
        val original = MtuDetector.Result(1400, MtuDetector.DetectionMethod.UDP_PROBE, 4)
        val copied = original.copy(mtu = 1300)
        assertEquals(1300, copied.mtu)
        assertEquals(MtuDetector.DetectionMethod.UDP_PROBE, copied.method)
        assertEquals(4, copied.probesSent)
    }

    @Test
    fun `detect returns result with mtu at least MIN_MTU`() {
        val detector = MtuDetector(timeoutMs = 500)
        // Use loopback which should be reachable
        val result = detector.detect(java.net.InetAddress.getLoopbackAddress())
        assertTrue("MTU should be >= MIN_MTU", result.mtu >= MtuDetector.MIN_MTU)
        assertTrue("Probes sent should be > 0", result.probesSent > 0)
        assertNotNull(result.method)
    }
}
