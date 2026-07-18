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
    fun `Transport enum has exactly three values`() {
        val values = Transport.entries
        assertEquals(3, values.size)
        assertEquals(Transport.WIFI_DIRECT, values[0])
        assertEquals(Transport.BLUETOOTH, values[1])
        assertEquals(Transport.USB, values[2])
    }

    @Test
    fun `Transport valueOf works`() {
        assertEquals(Transport.WIFI_DIRECT, Transport.valueOf("WIFI_DIRECT"))
        assertEquals(Transport.BLUETOOTH, Transport.valueOf("BLUETOOTH"))
        assertEquals(Transport.USB, Transport.valueOf("USB"))
    }

    @Test
    fun `equality includes transport`() {
        val a = VpnState(isConnected = true, transport = Transport.WIFI_DIRECT)
        val b = VpnState(isConnected = true, transport = Transport.BLUETOOTH)
        assertFalse(a == b)
    }

    @Test
    fun `toString contains key fields`() {
        val state = VpnState(isConnected = true, socksHost = "10.0.0.1")
        val str = state.toString()
        assertTrue(str.contains("isConnected=true"))
        assertTrue(str.contains("socksHost=10.0.0.1"))
    }

    @Test
    fun `destructuring works`() {
        val state = VpnState(isConnected = true, socksHost = "1.2.3.4", socksPort = 9999)
        val (connected, host, port) = state
        assertTrue(connected)
        assertEquals("1.2.3.4", host)
        assertEquals(9999, port)
    }

    @Test
    fun `copy with all fields changed`() {
        val state = VpnState()
        val modified = state.copy(
            isConnected = true,
            socksHost = "10.0.0.1",
            socksPort = 9050,
            transport = Transport.BLUETOOTH,
            error = "some error",
        )
        assertTrue(modified.isConnected)
        assertEquals("10.0.0.1", modified.socksHost)
        assertEquals(9050, modified.socksPort)
        assertEquals(Transport.BLUETOOTH, modified.transport)
        assertEquals("some error", modified.error)
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

    @Test
    fun `transport can be set to USB`() {
        val state = VpnState(transport = Transport.USB)
        assertEquals(Transport.USB, state.transport)
    }

    @Test
    fun `copy preserves USB transport`() {
        val state = VpnState(isConnected = true, transport = Transport.USB)
        val copied = state.copy(error = "test")
        assertEquals(Transport.USB, copied.transport)
        assertTrue(copied.isConnected)
    }

    @Test
    fun `usb state with loopback address`() {
        val state = VpnState(
            isConnected = true,
            socksHost = "127.0.0.1",
            socksPort = 54321,
            transport = Transport.USB,
        )
        assertEquals("127.0.0.1:54321", state.socksAddress)
        assertEquals(Transport.USB, state.transport)
    }

    @Test
    fun `equality distinguishes USB from other transports`() {
        val wifi = VpnState(isConnected = true, transport = Transport.WIFI_DIRECT)
        val bt = VpnState(isConnected = true, transport = Transport.BLUETOOTH)
        val usb = VpnState(isConnected = true, transport = Transport.USB)
        assertFalse(wifi == usb)
        assertFalse(bt == usb)
    }

    @Test
    fun `default errorType is NONE`() {
        val state = VpnState()
        assertEquals(ConnectionErrorType.NONE, state.errorType)
    }

    @Test
    fun `errorType can be set`() {
        val state = VpnState(errorType = ConnectionErrorType.HOST_NOT_FOUND)
        assertEquals(ConnectionErrorType.HOST_NOT_FOUND, state.errorType)
    }

    @Test
    fun `copy preserves errorType`() {
        val state = VpnState(errorType = ConnectionErrorType.TIMEOUT)
        val copied = state.copy(error = "test")
        assertEquals(ConnectionErrorType.TIMEOUT, copied.errorType)
    }

    @Test
    fun `ConnectionErrorType enum has all expected values`() {
        val values = ConnectionErrorType.entries
        assertEquals(7, values.size)
        assertTrue(values.contains(ConnectionErrorType.NONE))
        assertTrue(values.contains(ConnectionErrorType.HOST_NOT_FOUND))
        assertTrue(values.contains(ConnectionErrorType.AUTH_FAILED))
        assertTrue(values.contains(ConnectionErrorType.TIMEOUT))
        assertTrue(values.contains(ConnectionErrorType.VPN_DENIED))
        assertTrue(values.contains(ConnectionErrorType.NO_TRANSPORTS))
        assertTrue(values.contains(ConnectionErrorType.GENERIC))
    }

    @Test
    fun `default transport availability is all true`() {
        val state = VpnState()
        assertTrue(state.wifiAvailable)
        assertTrue(state.bluetoothAvailable)
        assertTrue(state.usbAvailable)
    }

    @Test
    fun `noTransportsAvailable when all unavailable`() {
        val state = VpnState(
            wifiAvailable = false,
            bluetoothAvailable = false,
            usbAvailable = false,
        )
        assertTrue(state.noTransportsAvailable)
    }

    @Test
    fun `noTransportsAvailable false when any available`() {
        val wifiOnly = VpnState(wifiAvailable = true, bluetoothAvailable = false, usbAvailable = false)
        assertFalse(wifiOnly.noTransportsAvailable)

        val btOnly = VpnState(wifiAvailable = false, bluetoothAvailable = true, usbAvailable = false)
        assertFalse(btOnly.noTransportsAvailable)

        val usbOnly = VpnState(wifiAvailable = false, bluetoothAvailable = false, usbAvailable = true)
        assertFalse(usbOnly.noTransportsAvailable)
    }

    @Test
    fun `copy preserves transport availability`() {
        val state = VpnState(wifiAvailable = false, bluetoothAvailable = true, usbAvailable = false)
        val copied = state.copy(isConnected = true)
        assertFalse(copied.wifiAvailable)
        assertTrue(copied.bluetoothAvailable)
        assertFalse(copied.usbAvailable)
    }

    @Test
    fun `equality includes transport availability`() {
        val a = VpnState(wifiAvailable = false)
        val b = VpnState(wifiAvailable = false)
        assertEquals(a, b)

        val c = VpnState(wifiAvailable = true)
        assertFalse(a == c)
    }
}
