package org.flossware.hotspot.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PeerStoreTest {

    private lateinit var store: PeerStore
    private var clockMs = 1_000_000L

    @Before
    fun setUp() {
        store = PeerStore(
            storage = InMemoryPeerStorage(),
            clock = { clockMs },
        )
    }

    @Test
    fun `addPeer creates trusted peer with correct fields`() {
        val peer = store.addPeer("key1", "Alice's Phone")
        assertEquals("key1", peer.publicKeyBase64)
        assertEquals("Alice's Phone", peer.label)
        assertEquals(clockMs, peer.pairedTimestamp)
        assertEquals(clockMs, peer.lastSeen)
    }

    @Test
    fun `addPeer makes peer trusted`() {
        store.addPeer("key1", "Device A")
        assertTrue(store.isTrusted("key1"))
    }

    @Test
    fun `getPeer returns stored peer`() {
        store.addPeer("key1", "Device A")
        val peer = store.getPeer("key1")
        assertNotNull(peer)
        assertEquals("Device A", peer!!.label)
    }

    @Test
    fun `getPeer returns null for unknown key`() {
        assertNull(store.getPeer("unknown"))
    }

    @Test
    fun `isTrusted returns false for unknown key`() {
        assertFalse(store.isTrusted("unknown"))
    }

    @Test
    fun `removePeer removes the peer`() {
        store.addPeer("key1", "Device A")
        assertTrue(store.removePeer("key1"))
        assertFalse(store.isTrusted("key1"))
    }

    @Test
    fun `removePeer returns false for unknown key`() {
        assertFalse(store.removePeer("unknown"))
    }

    @Test
    fun `listPeers returns all peers`() {
        store.addPeer("key1", "A")
        store.addPeer("key2", "B")
        store.addPeer("key3", "C")
        val peers = store.listPeers()
        assertEquals(3, peers.size)
        assertTrue(peers.any { it.publicKeyBase64 == "key1" })
        assertTrue(peers.any { it.publicKeyBase64 == "key2" })
        assertTrue(peers.any { it.publicKeyBase64 == "key3" })
    }

    @Test
    fun `listPeers returns empty list when no peers`() {
        assertTrue(store.listPeers().isEmpty())
    }

    @Test
    fun `size reflects number of peers`() {
        assertEquals(0, store.size)
        store.addPeer("key1", "A")
        assertEquals(1, store.size)
        store.addPeer("key2", "B")
        assertEquals(2, store.size)
        store.removePeer("key1")
        assertEquals(1, store.size)
    }

    @Test
    fun `updateLastSeen updates timestamp`() {
        store.addPeer("key1", "A")
        clockMs = 2_000_000L
        store.updateLastSeen("key1")
        val peer = store.getPeer("key1")!!
        assertEquals(2_000_000L, peer.lastSeen)
        assertEquals(1_000_000L, peer.pairedTimestamp)
    }

    @Test
    fun `updateLastSeen does nothing for unknown key`() {
        // Should not throw
        store.updateLastSeen("unknown")
    }

    @Test
    fun `clear removes all peers`() {
        store.addPeer("key1", "A")
        store.addPeer("key2", "B")
        store.clear()
        assertEquals(0, store.size)
        assertTrue(store.listPeers().isEmpty())
    }

    @Test
    fun `addPeer replaces existing peer with same key`() {
        store.addPeer("key1", "Old Label")
        clockMs = 2_000_000L
        store.addPeer("key1", "New Label")
        val peer = store.getPeer("key1")!!
        assertEquals("New Label", peer.label)
        assertEquals(2_000_000L, peer.pairedTimestamp)
        assertEquals(1, store.size)
    }

    @Test
    fun `peers are persisted to storage`() {
        val storage = InMemoryPeerStorage()
        val store1 = PeerStore(storage = storage, clock = { clockMs })
        store1.addPeer("key1", "A")

        val store2 = PeerStore(storage = storage, clock = { clockMs })
        assertTrue(store2.isTrusted("key1"))
        assertEquals("A", store2.getPeer("key1")!!.label)
    }

    @Test
    fun `InMemoryPeerStorage starts empty`() {
        val storage = InMemoryPeerStorage()
        assertTrue(storage.loadPeers().isEmpty())
    }

    @Test
    fun `InMemoryPeerStorage roundtrips peers`() {
        val storage = InMemoryPeerStorage()
        val peers = mapOf(
            "k1" to TrustedPeer("k1", "A", 1000L),
            "k2" to TrustedPeer("k2", "B", 2000L),
        )
        storage.savePeers(peers)
        assertEquals(peers, storage.loadPeers())
    }
}
