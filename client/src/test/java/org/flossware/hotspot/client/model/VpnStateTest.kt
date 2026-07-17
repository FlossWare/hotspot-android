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
}
