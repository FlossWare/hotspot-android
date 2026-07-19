package org.flossware.hotspot.client.network

import org.flossware.hotspot.client.model.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientMtuManagerTest {

    @Test
    fun `default timeout is 5000ms`() {
        assertEquals(5000, ClientMtuManager.DEFAULT_TIMEOUT_MS)
    }

    @Test
    fun `safety margin is 20 bytes`() {
        assertEquals(20, ClientMtuManager.SAFETY_MARGIN)
    }

    @Test
    fun `fallback MTU is 1280`() {
        assertEquals(1280, ClientMtuManager.FALLBACK_MTU)
    }

    @Test
    fun `minimum MTU is 576`() {
        assertEquals(576, ClientMtuManager.MIN_MTU)
    }

    @Test
    fun `maximum MTU is 1500`() {
        assertEquals(1500, ClientMtuManager.MAX_MTU)
    }

    @Test
    fun `minimum tunnel MTU is 1000`() {
        assertEquals(1000, ClientMtuManager.MIN_TUNNEL_MTU)
    }

    @Test
    fun `TCP IPv4 header size is 40`() {
        assertEquals(40, ClientMtuManager.TCP_IPV4_HEADER_SIZE)
    }

    @Test
    fun `TCP IPv6 header size is 60`() {
        assertEquals(60, ClientMtuManager.TCP_IPV6_HEADER_SIZE)
    }

    @Test
    fun `WiFi Direct overhead is 80 bytes`() {
        assertEquals(80, ClientMtuManager.WIFI_DIRECT_OVERHEAD)
    }

    @Test
    fun `Bluetooth overhead is 50 bytes`() {
        assertEquals(50, ClientMtuManager.BLUETOOTH_OVERHEAD)
    }

    @Test
    fun `USB overhead is 60 bytes`() {
        assertEquals(60, ClientMtuManager.USB_OVERHEAD)
    }

    @Test
    fun `cache max age is 30 days`() {
        val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000
        assertEquals(thirtyDaysMs, ClientMtuManager.CACHE_MAX_AGE_MS)
    }

    @Test
    fun `encapsulation overhead for WiFi Direct`() {
        assertEquals(80, ClientMtuManager.encapsulationOverhead(Transport.WIFI_DIRECT))
    }

    @Test
    fun `encapsulation overhead for Bluetooth`() {
        assertEquals(50, ClientMtuManager.encapsulationOverhead(Transport.BLUETOOTH))
    }

    @Test
    fun `encapsulation overhead for USB`() {
        assertEquals(60, ClientMtuManager.encapsulationOverhead(Transport.USB))
    }

    @Test
    fun `probe sizes are in ascending order`() {
        val sizes = ClientMtuManager.PROBE_SIZES
        for (i in 1 until sizes.size) {
            assertTrue(
                "Probe size ${sizes[i]} should be greater than ${sizes[i - 1]}",
                sizes[i] > sizes[i - 1],
            )
        }
    }

    @Test
    fun `probe sizes contain expected values`() {
        val sizes = ClientMtuManager.PROBE_SIZES.toList()
        assertTrue(sizes.contains(1180))
        assertTrue(sizes.contains(1280))
        assertTrue(sizes.contains(1380))
        assertTrue(sizes.contains(1420))
        assertTrue(sizes.contains(1460))
        assertTrue(sizes.contains(1500))
    }

    @Test
    fun `probe sizes has 6 entries`() {
        assertEquals(6, ClientMtuManager.PROBE_SIZES.size)
    }

    @Test
    fun `MtuInfo data class holds correct values`() {
        val info = ClientMtuManager.MtuInfo(
            mtu = 1400,
            tcpMssV4 = 1360,
            tcpMssV6 = 1340,
            fromCache = false,
            detectionMethod = ClientMtuManager.DetectionMethod.UDP_PROBE,
        )
        assertEquals(1400, info.mtu)
        assertEquals(1360, info.tcpMssV4)
        assertEquals(1340, info.tcpMssV6)
        assertFalse(info.fromCache)
        assertEquals(ClientMtuManager.DetectionMethod.UDP_PROBE, info.detectionMethod)
    }

    @Test
    fun `MtuInfo from cache has null detection method`() {
        val info = ClientMtuManager.MtuInfo(
            mtu = 1400,
            tcpMssV4 = 1360,
            tcpMssV6 = 1340,
            fromCache = true,
            detectionMethod = null,
        )
        assertTrue(info.fromCache)
        assertNull(info.detectionMethod)
    }

    @Test
    fun `MtuInfo equality works`() {
        val a = ClientMtuManager.MtuInfo(1400, 1360, 1340, false, ClientMtuManager.DetectionMethod.UDP_PROBE)
        val b = ClientMtuManager.MtuInfo(1400, 1360, 1340, false, ClientMtuManager.DetectionMethod.UDP_PROBE)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `MtuInfo copy works`() {
        val original = ClientMtuManager.MtuInfo(1400, 1360, 1340, false, ClientMtuManager.DetectionMethod.UDP_PROBE)
        val copied = original.copy(fromCache = true, detectionMethod = null)
        assertEquals(1400, copied.mtu)
        assertTrue(copied.fromCache)
        assertNull(copied.detectionMethod)
    }

    @Test
    fun `detection method enum has three values`() {
        val methods = ClientMtuManager.DetectionMethod.entries
        assertEquals(3, methods.size)
    }

    @Test
    fun `MtuInfo tcpMssV4 is mtu minus 40`() {
        val mtu = 1400
        val info = ClientMtuManager.MtuInfo(
            mtu = mtu,
            tcpMssV4 = mtu - ClientMtuManager.TCP_IPV4_HEADER_SIZE,
            tcpMssV6 = mtu - ClientMtuManager.TCP_IPV6_HEADER_SIZE,
            fromCache = false,
            detectionMethod = ClientMtuManager.DetectionMethod.UDP_PROBE,
        )
        assertEquals(mtu - 40, info.tcpMssV4)
    }

    @Test
    fun `MtuInfo tcpMssV6 is mtu minus 60`() {
        val mtu = 1400
        val info = ClientMtuManager.MtuInfo(
            mtu = mtu,
            tcpMssV4 = mtu - ClientMtuManager.TCP_IPV4_HEADER_SIZE,
            tcpMssV6 = mtu - ClientMtuManager.TCP_IPV6_HEADER_SIZE,
            fromCache = false,
            detectionMethod = ClientMtuManager.DetectionMethod.UDP_PROBE,
        )
        assertEquals(mtu - 60, info.tcpMssV6)
    }

    @Test
    fun `cache prefs name is client_mtu_cache`() {
        assertEquals("client_mtu_cache", ClientMtuManager.CACHE_PREFS_NAME)
    }

    @Test
    fun `UDP IP header size is 28`() {
        assertEquals(28, ClientMtuManager.UDP_IP_HEADER_SIZE)
    }

    @Test
    fun `all transport types have encapsulation overhead`() {
        for (transport in Transport.entries) {
            val overhead = ClientMtuManager.encapsulationOverhead(transport)
            assertTrue("Overhead for $transport should be > 0", overhead > 0)
        }
    }

    @Test
    fun `MtuInfo toString contains fields`() {
        val info = ClientMtuManager.MtuInfo(1400, 1360, 1340, false, ClientMtuManager.DetectionMethod.UDP_PROBE)
        val str = info.toString()
        assertNotNull(str)
        assertTrue(str.contains("1400"))
        assertTrue(str.contains("1360"))
        assertTrue(str.contains("1340"))
    }
}
