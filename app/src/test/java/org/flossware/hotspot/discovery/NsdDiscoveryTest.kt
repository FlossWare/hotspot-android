package org.flossware.hotspot.discovery

import org.flossware.hotspot.transport.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NsdDiscoveryTest {

    // --- parseTransports ---

    @Test
    fun `parseTransports returns empty set for null`() {
        val result = NsdDiscovery.parseTransports(null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseTransports returns empty set for blank string`() {
        val result = NsdDiscovery.parseTransports("")
        assertTrue(result.isEmpty())
        val result2 = NsdDiscovery.parseTransports("   ")
        assertTrue(result2.isEmpty())
    }

    @Test
    fun `parseTransports parses single transport`() {
        val result = NsdDiscovery.parseTransports("WIFI_DIRECT")
        assertEquals(setOf(TransportType.WIFI_DIRECT), result)
    }

    @Test
    fun `parseTransports parses multiple transports`() {
        val result = NsdDiscovery.parseTransports("WIFI_DIRECT,BLUETOOTH,USB")
        assertEquals(setOf(TransportType.WIFI_DIRECT, TransportType.BLUETOOTH, TransportType.USB), result)
    }

    @Test
    fun `parseTransports ignores unknown transport names`() {
        val result = NsdDiscovery.parseTransports("WIFI_DIRECT,INFRARED,BLUETOOTH")
        assertEquals(setOf(TransportType.WIFI_DIRECT, TransportType.BLUETOOTH), result)
    }

    @Test
    fun `parseTransports trims whitespace around names`() {
        val result = NsdDiscovery.parseTransports(" BLUETOOTH , USB ")
        assertEquals(setOf(TransportType.BLUETOOTH, TransportType.USB), result)
    }

    @Test
    fun `parseTransports returns empty set when all names are invalid`() {
        val result = NsdDiscovery.parseTransports("NFC,INFRARED,SATELLITE")
        assertTrue(result.isEmpty())
    }

    // --- DiscoveryResult ---

    @Test
    fun `DiscoveryResult default signal strength is SIGNAL_UNKNOWN`() {
        val result = DiscoveryResult(
            peerIdentity = org.flossware.hotspot.transport.PeerIdentity("fp", "label"),
            availableTransports = setOf(TransportType.WIFI_DIRECT),
        )
        assertEquals(DiscoveryResult.SIGNAL_UNKNOWN, result.signalStrength)
    }

    @Test
    fun `DiscoveryResult default discovery method is NSD`() {
        val result = DiscoveryResult(
            peerIdentity = org.flossware.hotspot.transport.PeerIdentity("fp", "label"),
            availableTransports = setOf(TransportType.BLUETOOTH),
        )
        assertEquals(DiscoveryMethod.NSD, result.discoveryMethod)
    }

    @Test
    fun `DiscoveryResult holds custom values`() {
        val peer = org.flossware.hotspot.transport.PeerIdentity("sha256:abc", "Phone")
        val result = DiscoveryResult(
            peerIdentity = peer,
            availableTransports = setOf(TransportType.WIFI_DIRECT, TransportType.USB),
            signalStrength = 85,
            discoveryMethod = DiscoveryMethod.MANUAL,
        )
        assertEquals(peer, result.peerIdentity)
        assertEquals(setOf(TransportType.WIFI_DIRECT, TransportType.USB), result.availableTransports)
        assertEquals(85, result.signalStrength)
        assertEquals(DiscoveryMethod.MANUAL, result.discoveryMethod)
    }

    // --- NsdDiscovery constants ---

    @Test
    fun `SERVICE_TYPE follows mDNS convention`() {
        assertEquals("_flossware._tcp.", NsdDiscovery.SERVICE_TYPE)
    }

    @Test
    fun `SERVICE_NAME is FlossHotspot`() {
        assertEquals("FlossHotspot", NsdDiscovery.SERVICE_NAME)
    }

    @Test
    fun `PROTOCOL_VERSION is 1`() {
        assertEquals(1, NsdDiscovery.PROTOCOL_VERSION)
    }

    @Test
    fun `TXT record keys are short to stay within DNS-SD limits`() {
        // DNS-SD TXT keys should be <= 9 chars for compatibility
        assertTrue(NsdDiscovery.TXT_KEY_FINGERPRINT.length <= 9)
        assertTrue(NsdDiscovery.TXT_KEY_VERSION.length <= 9)
        assertTrue(NsdDiscovery.TXT_KEY_TRANSPORTS.length <= 9)
    }
}
