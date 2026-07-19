package org.flossware.hotspot.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MtuCacheTest {

    @Test
    fun `prefs name is mtu_cache`() {
        assertEquals("mtu_cache", MtuCache.PREFS_NAME)
    }

    @Test
    fun `max age is 30 days in milliseconds`() {
        val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000
        assertEquals(thirtyDaysMs, MtuCache.MAX_AGE_MS)
    }

    @Test
    fun `buildKey combines transport and peer`() {
        val key = MtuCache.buildKey("wifi_direct", "192.168.49.1")
        assertEquals("wifi_direct:192.168.49.1", key)
    }

    @Test
    fun `buildKey with bluetooth transport`() {
        val key = MtuCache.buildKey("bluetooth", "AA:BB:CC:DD:EE:FF")
        assertEquals("bluetooth:AA:BB:CC:DD:EE:FF", key)
    }

    @Test
    fun `buildKey with usb transport`() {
        val key = MtuCache.buildKey("usb", "device0")
        assertEquals("usb:device0", key)
    }

    @Test
    fun `buildKey with empty peer identifier`() {
        val key = MtuCache.buildKey("wifi_direct", "")
        assertEquals("wifi_direct:", key)
    }

    @Test
    fun `entry data class holds correct values`() {
        val entry = MtuCache.Entry(mtu = 1400, timestampMs = 1000L)
        assertEquals(1400, entry.mtu)
        assertEquals(1000L, entry.timestampMs)
    }

    @Test
    fun `entry equality works`() {
        val a = MtuCache.Entry(1400, 1000L)
        val b = MtuCache.Entry(1400, 1000L)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `entry copy works`() {
        val original = MtuCache.Entry(1400, 1000L)
        val copied = original.copy(mtu = 1300)
        assertEquals(1300, copied.mtu)
        assertEquals(1000L, copied.timestampMs)
    }

    @Test
    fun `entry toString contains fields`() {
        val entry = MtuCache.Entry(1400, 1000L)
        val str = entry.toString()
        assertNotNull(str)
        assertEquals("Entry(mtu=1400, timestampMs=1000)", str)
    }

    @Test
    fun `entry destructuring works`() {
        val entry = MtuCache.Entry(1400, 1000L)
        val (mtu, timestamp) = entry
        assertEquals(1400, mtu)
        assertEquals(1000L, timestamp)
    }

    @Test
    fun `max age is positive`() {
        assert(MtuCache.MAX_AGE_MS > 0)
    }

    @Test
    fun `buildKey is deterministic`() {
        val key1 = MtuCache.buildKey("wifi_direct", "peer1")
        val key2 = MtuCache.buildKey("wifi_direct", "peer1")
        assertEquals(key1, key2)
    }

    @Test
    fun `buildKey produces different keys for different transports`() {
        val wifiKey = MtuCache.buildKey("wifi_direct", "peer1")
        val btKey = MtuCache.buildKey("bluetooth", "peer1")
        assert(wifiKey != btKey)
    }

    @Test
    fun `buildKey produces different keys for different peers`() {
        val key1 = MtuCache.buildKey("wifi_direct", "peer1")
        val key2 = MtuCache.buildKey("wifi_direct", "peer2")
        assert(key1 != key2)
    }
}
