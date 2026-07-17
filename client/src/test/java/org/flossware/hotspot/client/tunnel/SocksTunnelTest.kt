package org.flossware.hotspot.client.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SocksTunnelTest {

    @Test
    fun `buildConfig contains socks5 address`() {
        val config = SocksTunnel.buildConfig("192.168.49.1", 1080)
        assertTrue(config.contains("address: 192.168.49.1"))
    }

    @Test
    fun `buildConfig contains socks5 port`() {
        val config = SocksTunnel.buildConfig("192.168.49.1", 1080)
        assertTrue(config.contains("port: 1080"))
    }

    @Test
    fun `buildConfig with custom host and port`() {
        val config = SocksTunnel.buildConfig("10.0.0.5", 9050)
        assertTrue(config.contains("address: 10.0.0.5"))
        assertTrue(config.contains("port: 9050"))
    }

    @Test
    fun `buildConfig contains tunnel mtu`() {
        val config = SocksTunnel.buildConfig("192.168.49.1", 1080)
        assertTrue(config.contains("mtu: 1500"))
    }

    @Test
    fun `buildConfig contains misc settings`() {
        val config = SocksTunnel.buildConfig("192.168.49.1", 1080)
        assertTrue(config.contains("connect-timeout: 5000"))
        assertTrue(config.contains("read-write-timeout: 60000"))
        assertTrue(config.contains("limit-nofile: 65535"))
        assertTrue(config.contains("log-level: warn"))
    }

    @Test
    fun `buildConfig is valid YAML structure`() {
        val config = SocksTunnel.buildConfig("192.168.49.1", 1080)
        assertTrue(config.startsWith("tunnel:"))
        assertTrue(config.contains("socks5:"))
        assertTrue(config.contains("misc:"))
    }

    @Test
    fun `buildConfig has no leading whitespace on top-level keys`() {
        val config = SocksTunnel.buildConfig("192.168.49.1", 1080)
        val lines = config.lines()
        val topLevelKeys = lines.filter { it.endsWith(":") && !it.startsWith(" ") }
        assertEquals(3, topLevelKeys.size)
        assertTrue(topLevelKeys.contains("tunnel:"))
        assertTrue(topLevelKeys.contains("socks5:"))
        assertTrue(topLevelKeys.contains("misc:"))
    }

    @Test
    fun `config filename is tun2socks yml`() {
        assertEquals("tun2socks.yml", SocksTunnel.CONFIG_FILENAME)
    }

    @Test
    fun `TrafficStats holds values`() {
        val stats = SocksTunnel.TrafficStats(100, 2048, 50, 1024)
        assertEquals(100, stats.txPackets)
        assertEquals(2048, stats.txBytes)
        assertEquals(50, stats.rxPackets)
        assertEquals(1024, stats.rxBytes)
    }

    @Test
    fun `TrafficStats equality`() {
        val a = SocksTunnel.TrafficStats(1, 2, 3, 4)
        val b = SocksTunnel.TrafficStats(1, 2, 3, 4)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `TrafficStats inequality`() {
        val a = SocksTunnel.TrafficStats(1, 2, 3, 4)
        val b = SocksTunnel.TrafficStats(1, 2, 3, 5)
        assertFalse(a == b)
    }

    @Test
    fun `TrafficStats zero values`() {
        val stats = SocksTunnel.TrafficStats(0, 0, 0, 0)
        assertEquals(0, stats.txPackets)
        assertEquals(0, stats.txBytes)
        assertEquals(0, stats.rxPackets)
        assertEquals(0, stats.rxBytes)
    }
}
