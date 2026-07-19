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
        assertEquals("", state.configuredPassphrase)
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
        assertFalse(state.bluetoothOptIn)
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
    fun `default power management fields are correct`() {
        val state = HotspotState()
        assertEquals(0L, state.uptimeSeconds)
        assertFalse(state.isIdle)
    }

    @Test
    fun `uptimeSeconds can be set`() {
        val state = HotspotState(uptimeSeconds = 3600)
        assertEquals(3600L, state.uptimeSeconds)
    }

    @Test
    fun `isIdle can be set`() {
        val state = HotspotState(isIdle = true)
        assertTrue(state.isIdle)
    }

    @Test
    fun `copy preserves power management fields`() {
        val state = HotspotState(
            uptimeSeconds = 120,
            isIdle = true,
        )
        val copied = state.copy(isRunning = true)
        assertEquals(120L, copied.uptimeSeconds)
        assertTrue(copied.isIdle)
    }

    @Test
    fun `equality includes power management fields`() {
        val a = HotspotState(uptimeSeconds = 60, isIdle = false)
        val b = HotspotState(uptimeSeconds = 60, isIdle = false)
        assertEquals(a, b)

        val c = HotspotState(uptimeSeconds = 60, isIdle = true)
        assertFalse(a == c)
    }

    @Test
    fun `bluetooth fields can be set`() {
        val btDevices = listOf(ConnectedDevice("11:22:33:44:55:66", "Pixel"))
        val state = HotspotState(
            bluetoothOptIn = true,
            bluetoothEnabled = true,
            bluetoothDeviceName = "Galaxy S24",
            bluetoothConnectedDevices = btDevices,
        )
        assertTrue(state.bluetoothOptIn)
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
            bluetoothOptIn = true,
            bluetoothEnabled = true,
            bluetoothDeviceName = "Test BT",
            dnsCacheHits = 50,
            httpCacheHits = 25,
            dataSaved = 2048,
        )
        val copied = state.copy(isRunning = true)
        assertTrue(copied.bluetoothOptIn)
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
        val a = HotspotState(bluetoothOptIn = true, bluetoothEnabled = true, dnsCacheHits = 10)
        val b = HotspotState(bluetoothOptIn = true, bluetoothEnabled = true, dnsCacheHits = 10)
        assertEquals(a, b)

        val c = HotspotState(bluetoothOptIn = false, bluetoothEnabled = true, dnsCacheHits = 10)
        assertFalse(a == c)

        val d = HotspotState(bluetoothOptIn = true, bluetoothEnabled = false, dnsCacheHits = 10)
        assertFalse(a == d)
    }

    @Test
    fun `bluetoothOptIn defaults to false`() {
        val state = HotspotState()
        assertFalse(state.bluetoothOptIn)
    }

    @Test
    fun `bluetoothOptIn can be toggled independently of bluetoothEnabled`() {
        val state = HotspotState(bluetoothOptIn = true, bluetoothEnabled = false)
        assertTrue(state.bluetoothOptIn)
        assertFalse(state.bluetoothEnabled)
    }

    @Test
    fun `default usbConnected is false`() {
        val state = HotspotState()
        assertFalse(state.usbConnected)
    }

    @Test
    fun `usbConnected can be set`() {
        val state = HotspotState(usbConnected = true)
        assertTrue(state.usbConnected)
    }

    @Test
    fun `copy preserves usbConnected`() {
        val state = HotspotState(usbConnected = true)
        val copied = state.copy(isRunning = true)
        assertTrue(copied.usbConnected)
    }

    @Test
    fun `equality includes usbConnected`() {
        val a = HotspotState(usbConnected = true)
        val b = HotspotState(usbConnected = true)
        assertEquals(a, b)

        val c = HotspotState(usbConnected = false)
        assertFalse(a == c)
    }

    @Test
    fun `default feature availability fields are true`() {
        val state = HotspotState()
        assertTrue(state.wifiDirectAvailable)
        assertTrue(state.bluetoothAvailable)
        assertTrue(state.usbAvailable)
        assertTrue(state.mobileDataAvailable)
        assertFalse(state.permissionsDenied)
        assertFalse(state.bluetoothOnlyMode)
    }

    @Test
    fun `canFallbackToBluetooth when wifi direct unavailable and bluetooth available`() {
        val state = HotspotState(
            wifiDirectAvailable = false,
            bluetoothAvailable = true,
            isRunning = false,
        )
        assertTrue(state.canFallbackToBluetooth)
    }

    @Test
    fun `canFallbackToBluetooth false when wifi direct available`() {
        val state = HotspotState(
            wifiDirectAvailable = true,
            bluetoothAvailable = true,
            isRunning = false,
        )
        assertFalse(state.canFallbackToBluetooth)
    }

    @Test
    fun `canFallbackToBluetooth false when bluetooth unavailable`() {
        val state = HotspotState(
            wifiDirectAvailable = false,
            bluetoothAvailable = false,
            isRunning = false,
        )
        assertFalse(state.canFallbackToBluetooth)
    }

    @Test
    fun `canFallbackToBluetooth false when running`() {
        val state = HotspotState(
            wifiDirectAvailable = false,
            bluetoothAvailable = true,
            isRunning = true,
        )
        assertFalse(state.canFallbackToBluetooth)
    }

    @Test
    fun `feature availability fields can be set`() {
        val state = HotspotState(
            wifiDirectAvailable = false,
            bluetoothAvailable = false,
            usbAvailable = false,
            mobileDataAvailable = false,
            permissionsDenied = true,
            bluetoothOnlyMode = true,
        )
        assertFalse(state.wifiDirectAvailable)
        assertFalse(state.bluetoothAvailable)
        assertFalse(state.usbAvailable)
        assertFalse(state.mobileDataAvailable)
        assertTrue(state.permissionsDenied)
        assertTrue(state.bluetoothOnlyMode)
    }

    @Test
    fun `copy preserves feature availability fields`() {
        val state = HotspotState(
            wifiDirectAvailable = false,
            bluetoothAvailable = true,
            usbAvailable = false,
            mobileDataAvailable = true,
            permissionsDenied = true,
            bluetoothOnlyMode = true,
        )
        val copied = state.copy(isRunning = true)
        assertFalse(copied.wifiDirectAvailable)
        assertTrue(copied.bluetoothAvailable)
        assertFalse(copied.usbAvailable)
        assertTrue(copied.mobileDataAvailable)
        assertTrue(copied.permissionsDenied)
        assertTrue(copied.bluetoothOnlyMode)
    }

    @Test
    fun `equality includes feature availability fields`() {
        val a = HotspotState(wifiDirectAvailable = false, permissionsDenied = true)
        val b = HotspotState(wifiDirectAvailable = false, permissionsDenied = true)
        assertEquals(a, b)

        val c = HotspotState(wifiDirectAvailable = true, permissionsDenied = true)
        assertFalse(a == c)
    }

    @Test
    fun `configuredPassphrase can be set`() {
        val state = HotspotState(configuredPassphrase = "MySecurePass")
        assertEquals("MySecurePass", state.configuredPassphrase)
    }

    @Test
    fun `copy preserves configuredPassphrase`() {
        val state = HotspotState(configuredPassphrase = "TestPass123")
        val copied = state.copy(isRunning = true)
        assertEquals("TestPass123", copied.configuredPassphrase)
    }

    @Test
    fun `equality includes configuredPassphrase`() {
        val a = HotspotState(configuredPassphrase = "pass1")
        val b = HotspotState(configuredPassphrase = "pass1")
        assertEquals(a, b)

        val c = HotspotState(configuredPassphrase = "pass2")
        assertFalse(a == c)
    }

    @Test
    fun `default pairing fields are correct`() {
        val state = HotspotState()
        assertFalse(state.pairingRequired)
        assertEquals("", state.pairingFingerprint)
        assertEquals(0, state.pairedDeviceCount)
    }

    @Test
    fun `pairing fields can be set`() {
        val state = HotspotState(
            pairingRequired = true,
            pairingFingerprint = "abcd1234",
            pairedDeviceCount = 3,
        )
        assertTrue(state.pairingRequired)
        assertEquals("abcd1234", state.pairingFingerprint)
        assertEquals(3, state.pairedDeviceCount)
    }

    @Test
    fun `copy preserves pairing fields`() {
        val state = HotspotState(
            pairingRequired = true,
            pairingFingerprint = "deadbeef",
            pairedDeviceCount = 2,
        )
        val copied = state.copy(isRunning = true)
        assertTrue(copied.pairingRequired)
        assertEquals("deadbeef", copied.pairingFingerprint)
        assertEquals(2, copied.pairedDeviceCount)
    }

    @Test
    fun `equality includes pairing fields`() {
        val a = HotspotState(pairingRequired = true, pairedDeviceCount = 1)
        val b = HotspotState(pairingRequired = true, pairedDeviceCount = 1)
        assertEquals(a, b)

        val c = HotspotState(pairingRequired = false, pairedDeviceCount = 1)
        assertFalse(a == c)
    fun `default watchdog fields are healthy`() {
        val state = HotspotState()
        assertTrue(state.isHealthy)
        assertFalse(state.isDegraded)
    }

    @Test
    fun `isHealthy can be set to false`() {
        val state = HotspotState(isHealthy = false)
        assertFalse(state.isHealthy)
    }

    @Test
    fun `isDegraded can be set to true`() {
        val state = HotspotState(isDegraded = true)
        assertTrue(state.isDegraded)
    }

    @Test
    fun `copy preserves watchdog fields`() {
        val state = HotspotState(isHealthy = false, isDegraded = true)
        val copied = state.copy(isRunning = true)
        assertFalse(copied.isHealthy)
        assertTrue(copied.isDegraded)
    }

    @Test
    fun `equality includes watchdog fields`() {
        val a = HotspotState(isHealthy = false, isDegraded = true)
        val b = HotspotState(isHealthy = false, isDegraded = true)
        assertEquals(a, b)

        val c = HotspotState(isHealthy = true, isDegraded = true)
        assertFalse(a == c)

        val d = HotspotState(isHealthy = false, isDegraded = false)
    fun `default detectedMtu is null`() {
        val state = HotspotState()
        assertNull(state.detectedMtu)
    }

    @Test
    fun `detectedMtu can be set`() {
        val state = HotspotState(detectedMtu = 1400)
        assertEquals(1400, state.detectedMtu)
    }

    @Test
    fun `copy preserves detectedMtu`() {
        val state = HotspotState(detectedMtu = 1420)
        val copied = state.copy(isRunning = true)
        assertEquals(1420, copied.detectedMtu)
    }

    @Test
    fun `equality includes detectedMtu`() {
        val a = HotspotState(detectedMtu = 1400)
        val b = HotspotState(detectedMtu = 1400)
        assertEquals(a, b)

        val c = HotspotState(detectedMtu = 1500)
        assertFalse(a == c)

        val d = HotspotState(detectedMtu = null)
        assertFalse(a == d)
    }
}
