package org.flossware.hotspot.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerIdentityTest {

    @Test
    fun `PeerIdentity holds all fields`() {
        val endpoints = mapOf(
            TransportType.WIFI_DIRECT to "192.168.49.1:7265",
            TransportType.BLUETOOTH to "AA:BB:CC:DD:EE:FF",
        )
        val peer = PeerIdentity(
            publicKeyFingerprint = "sha256:abc123",
            label = "TestDevice",
            endpoints = endpoints,
            protocolVersion = 2,
        )

        assertEquals("sha256:abc123", peer.publicKeyFingerprint)
        assertEquals("TestDevice", peer.label)
        assertEquals(endpoints, peer.endpoints)
        assertEquals(2, peer.protocolVersion)
    }

    @Test
    fun `default values for optional fields`() {
        val peer = PeerIdentity(
            publicKeyFingerprint = "sha256:def456",
            label = "MinimalPeer",
        )

        assertTrue(peer.endpoints.isEmpty())
        assertEquals(1, peer.protocolVersion)
    }

    @Test
    fun `equality based on all fields`() {
        val peer1 = PeerIdentity("fp1", "Device A")
        val peer2 = PeerIdentity("fp1", "Device A")
        val peer3 = PeerIdentity("fp2", "Device A")
        val peer4 = PeerIdentity("fp1", "Device B")

        assertEquals(peer1, peer2)
        assertNotEquals(peer1, peer3)
        assertNotEquals(peer1, peer4)
    }

    @Test
    fun `copy preserves unchanged fields`() {
        val original = PeerIdentity(
            publicKeyFingerprint = "fp",
            label = "Original",
            endpoints = mapOf(TransportType.USB to "usb:0"),
            protocolVersion = 3,
        )
        val copy = original.copy(label = "Updated")

        assertEquals("fp", copy.publicKeyFingerprint)
        assertEquals("Updated", copy.label)
        assertEquals(mapOf(TransportType.USB to "usb:0"), copy.endpoints)
        assertEquals(3, copy.protocolVersion)
    }

    @Test
    fun `hashCode consistent with equals`() {
        val peer1 = PeerIdentity("fp", "Name")
        val peer2 = PeerIdentity("fp", "Name")
        assertEquals(peer1.hashCode(), peer2.hashCode())
    }

    @Test
    fun `toString includes key fields`() {
        val peer = PeerIdentity("sha256:xyz", "MyPhone")
        val str = peer.toString()
        assertTrue(str.contains("sha256:xyz"))
        assertTrue(str.contains("MyPhone"))
    }
}
