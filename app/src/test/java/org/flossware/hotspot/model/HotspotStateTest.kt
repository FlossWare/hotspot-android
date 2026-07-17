package org.flossware.hotspot.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotStateTest {

    @Test
    fun `default state has correct values`() {
        val state = HotspotState()
        assertFalse(state.isRunning)
        assertEquals("", state.networkName)
        assertEquals("", state.passphrase)
        assertEquals("192.168.49.1", state.socksHost)
        assertEquals(1080, state.socksPort)
        assertEquals(5353, state.dnsPort)
        assertTrue(state.connectedDevices.isEmpty())
        assertNull(state.error)
        assertEquals(0L, state.bytesTransferred)
    }

    @Test
    fun `socksAddress formats correctly`() {
        val state = HotspotState()
        assertEquals("192.168.49.1:1080", state.socksAddress)
    }

    @Test
    fun `socksAddress with custom host and port`() {
        val state = HotspotState(socksHost = "10.0.0.1", socksPort = 9090)
        assertEquals("10.0.0.1:9090", state.socksAddress)
    }

    @Test
    fun `dnsAddress formats correctly`() {
        val state = HotspotState()
        assertEquals("192.168.49.1:5353", state.dnsAddress)
    }

    @Test
    fun `dnsAddress with custom host and port`() {
        val state = HotspotState(socksHost = "10.0.0.1", dnsPort = 5354)
        assertEquals("10.0.0.1:5354", state.dnsAddress)
    }

    @Test
    fun `copy preserves fields correctly`() {
        val devices = listOf(ConnectedDevice("AA:BB:CC:DD:EE:FF", "Phone"))
        val state = HotspotState(
            isRunning = true,
            networkName = "DIRECT-FW-Test",
            passphrase = "secret123",
            connectedDevices = devices,
            bytesTransferred = 1024,
        )
        val copied = state.copy(bytesTransferred = 2048)
        assertTrue(copied.isRunning)
        assertEquals("DIRECT-FW-Test", copied.networkName)
        assertEquals("secret123", copied.passphrase)
        assertEquals(devices, copied.connectedDevices)
        assertEquals(2048L, copied.bytesTransferred)
    }

    @Test
    fun `copy with error preserves other fields`() {
        val state = HotspotState(isRunning = true, networkName = "Test")
        val withError = state.copy(error = "Network lost")
        assertTrue(withError.isRunning)
        assertEquals("Test", withError.networkName)
        assertEquals("Network lost", withError.error)
    }

    @Test
    fun `companion constants are correct`() {
        assertEquals("192.168.49.1", HotspotState.DEFAULT_HOST)
        assertEquals(1080, HotspotState.DEFAULT_SOCKS_PORT)
        assertEquals(5353, HotspotState.DEFAULT_DNS_PORT)
    }

    @Test
    fun `equality works for identical states`() {
        val a = HotspotState(isRunning = true, networkName = "Net")
        val b = HotspotState(isRunning = true, networkName = "Net")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `inequality for different states`() {
        val a = HotspotState(isRunning = true)
        val b = HotspotState(isRunning = false)
        assertFalse(a == b)
    }
}
