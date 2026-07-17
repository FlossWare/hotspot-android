package org.flossware.hotspot.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectedDeviceTest {

    @Test
    fun `default ipAddress is null`() {
        val device = ConnectedDevice("AA:BB:CC:DD:EE:FF", "Phone-1")
        assertNull(device.ipAddress)
    }

    @Test
    fun `all fields are set correctly`() {
        val device = ConnectedDevice(
            macAddress = "11:22:33:44:55:66",
            deviceName = "Galaxy S24",
            ipAddress = "192.168.49.2",
        )
        assertEquals("11:22:33:44:55:66", device.macAddress)
        assertEquals("Galaxy S24", device.deviceName)
        assertEquals("192.168.49.2", device.ipAddress)
    }

    @Test
    fun `equality for identical devices`() {
        val a = ConnectedDevice("AA:BB:CC:DD:EE:FF", "Phone", "192.168.49.2")
        val b = ConnectedDevice("AA:BB:CC:DD:EE:FF", "Phone", "192.168.49.2")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `inequality for different mac addresses`() {
        val a = ConnectedDevice("AA:BB:CC:DD:EE:FF", "Phone")
        val b = ConnectedDevice("11:22:33:44:55:66", "Phone")
        assertFalse(a == b)
    }

    @Test
    fun `copy works correctly`() {
        val device = ConnectedDevice("AA:BB:CC:DD:EE:FF", "Phone")
        val updated = device.copy(ipAddress = "192.168.49.3")
        assertEquals("AA:BB:CC:DD:EE:FF", updated.macAddress)
        assertEquals("Phone", updated.deviceName)
        assertEquals("192.168.49.3", updated.ipAddress)
    }

    @Test
    fun `toString contains all fields`() {
        val device = ConnectedDevice("AA:BB:CC:DD:EE:FF", "TestPhone")
        val str = device.toString()
        assert(str.contains("AA:BB:CC:DD:EE:FF"))
        assert(str.contains("TestPhone"))
    }
}
