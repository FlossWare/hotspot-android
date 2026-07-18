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
    fun `default bluetooth fields are correct`() {
        val state = HotspotState()
        assertFalse(state.bluetoothEnabled)
        assertEquals("", state.bluetoothDeviceName)
        assertTrue(state.bluetoothConnectedDevices.isEmpty())
    }

    @Test
    fun `default cache fields are zero`() {
        val state = HotspotState()
        assertEquals(0L, state.dnsCacheHits)
        assertEquals(0L, state.httpCacheHits)
        assertEquals(0L, state.dataSaved)
    }

    @Test
    fun `bluetooth fields can be set`() {
        val btDevices = listOf(ConnectedDevice("11:22:33:44:55:66", "Pixel"))
        val state = HotspotState(
            bluetoothEnabled = true,
            bluetoothDeviceName = "Galaxy S24",
            bluetoothConnectedDevices = btDevices,
        )
        assertTrue(state.bluetoothEnabled)
        assertEquals("Galaxy S24", state.bluetoothDeviceName)
        assertEquals(btDevices, state.bluetoothConnectedDevices)
    }

    @Test
    fun `cache fields can be set`() {
        val state = HotspotState(
            dnsCacheHits = 100,
            httpCacheHits = 42,
            dataSaved = 1024 * 1024,
        )
        assertEquals(100L, state.dnsCacheHits)
        assertEquals(42L, state.httpCacheHits)
        assertEquals(1024L * 1024, state.dataSaved)
    }

    @Test
    fun `copy preserves bluetooth and cache fields`() {
        val state = HotspotState(
            bluetoothEnabled = true,
            bluetoothDeviceName = "Test BT",
            dnsCacheHits = 50,
            httpCacheHits = 25,
            dataSaved = 2048,
        )
        val copied = state.copy(isRunning = true)
        assertTrue(copied.bluetoothEnabled)
        assertEquals("Test BT", copied.bluetoothDeviceName)
        assertEquals(50L, copied.dnsCacheHits)
        assertEquals(25L, copied.httpCacheHits)
        assertEquals(2048L, copied.dataSaved)
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

    @Test
    fun `toString contains key fields`() {
        val state = HotspotState(isRunning = true, networkName = "TestNet")
        val str = state.toString()
        assertTrue(str.contains("isRunning=true"))
        assertTrue(str.contains("networkName=TestNet"))
    }

    @Test
    fun `destructuring works`() {
        val state = HotspotState(isRunning = true, networkName = "Net", passphrase = "pass")
        val (running, name, pass) = state
        assertTrue(running)
        assertEquals("Net", name)
        assertEquals("pass", pass)
    }

    @Test
    fun `multiple connected devices`() {
        val devices = listOf(
            ConnectedDevice("AA:BB:CC:DD:EE:01", "Phone1"),
            ConnectedDevice("AA:BB:CC:DD:EE:02", "Phone2"),
            ConnectedDevice("AA:BB:CC:DD:EE:03", "Phone3"),
        )
        val state = HotspotState(connectedDevices = devices)
        assertEquals(3, state.connectedDevices.size)
        assertEquals("Phone2", state.connectedDevices[1].deviceName)
    }

    @Test
    fun `equality includes bluetooth and cache fields`() {
        val a = HotspotState(bluetoothEnabled = true, dnsCacheHits = 10)
        val b = HotspotState(bluetoothEnabled = true, dnsCacheHits = 10)
        assertEquals(a, b)

        val c = HotspotState(bluetoothEnabled = false, dnsCacheHits = 10)
        assertFalse(a == c)
    }
}
