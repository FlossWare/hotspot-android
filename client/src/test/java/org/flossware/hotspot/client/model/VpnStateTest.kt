package org.flossware.hotspot.client.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnStateTest {

    @Test
    fun `default state has correct values`() {
        val state = VpnState()
        assertFalse(state.isConnected)
        assertEquals("192.168.49.1", state.socksHost)
        assertEquals(1080, state.socksPort)
        assertNull(state.error)
    }

    @Test
    fun `socksAddress formats correctly`() {
        val state = VpnState()
        assertEquals("192.168.49.1:1080", state.socksAddress)
    }

    @Test
    fun `socksAddress with custom host and port`() {
        val state = VpnState(socksHost = "10.0.0.1", socksPort = 9090)
        assertEquals("10.0.0.1:9090", state.socksAddress)
    }

    @Test
    fun `copy preserves fields`() {
        val state = VpnState(isConnected = true, socksHost = "10.0.0.1")
        val copied = state.copy(error = "test error")
        assertTrue(copied.isConnected)
        assertEquals("10.0.0.1", copied.socksHost)
        assertEquals("test error", copied.error)
    }

    @Test
    fun `equality works`() {
        val a = VpnState(isConnected = true)
        val b = VpnState(isConnected = true)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `inequality for different states`() {
        val a = VpnState(isConnected = true)
        val b = VpnState(isConnected = false)
        assertFalse(a == b)
    }

    @Test
    fun `companion constants are correct`() {
        assertEquals("192.168.49.1", VpnState.DEFAULT_SOCKS_HOST)
        assertEquals(1080, VpnState.DEFAULT_SOCKS_PORT)
    }

    @Test
    fun `default transport is WIFI_DIRECT`() {
        val state = VpnState()
        assertEquals(Transport.WIFI_DIRECT, state.transport)
    }

    @Test
    fun `transport can be set to BLUETOOTH`() {
        val state = VpnState(transport = Transport.BLUETOOTH)
        assertEquals(Transport.BLUETOOTH, state.transport)
    }

    @Test
    fun `copy preserves transport`() {
        val state = VpnState(isConnected = true, transport = Transport.BLUETOOTH)
        val copied = state.copy(error = "test")
        assertEquals(Transport.BLUETOOTH, copied.transport)
        assertTrue(copied.isConnected)
    }

    @Test
    fun `Transport enum has exactly two values`() {
        val values = Transport.entries
        assertEquals(2, values.size)
        assertEquals(Transport.WIFI_DIRECT, values[0])
        assertEquals(Transport.BLUETOOTH, values[1])
    }

    @Test
    fun `Transport valueOf works`() {
        assertEquals(Transport.WIFI_DIRECT, Transport.valueOf("WIFI_DIRECT"))
        assertEquals(Transport.BLUETOOTH, Transport.valueOf("BLUETOOTH"))
    }

    @Test
    fun `equality includes transport`() {
        val a = VpnState(isConnected = true, transport = Transport.WIFI_DIRECT)
        val b = VpnState(isConnected = true, transport = Transport.BLUETOOTH)
        assertFalse(a == b)
    }

    @Test
    fun `bluetooth state with loopback address`() {
        val state = VpnState(
            isConnected = true,
            socksHost = "127.0.0.1",
            socksPort = 12345,
            transport = Transport.BLUETOOTH,
        )
        assertEquals("127.0.0.1:12345", state.socksAddress)
        assertEquals(Transport.BLUETOOTH, state.transport)
    }
}
