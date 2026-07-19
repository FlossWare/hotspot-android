package org.flossware.hotspot.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IdentityManagerTest {

    private lateinit var manager: IdentityManager

    @Before
    fun setUp() {
        manager = IdentityManager(InMemoryKeyStorage())
    }

    @Test
    fun `getOrCreateKeyPair generates keypair on first call`() {
        val keyPair = manager.getOrCreateKeyPair()
        assertNotNull(keyPair)
        assertNotNull(keyPair.public)
        assertNotNull(keyPair.private)
    }

    @Test
    fun `getOrCreateKeyPair returns same keypair on subsequent calls`() {
        val first = manager.getOrCreateKeyPair()
        val second = manager.getOrCreateKeyPair()
        assertArrayEquals(first.public.encoded, second.public.encoded)
        assertArrayEquals(first.private.encoded, second.private.encoded)
    }

    @Test
    fun `getPublicKey returns non-empty encoded key`() {
        val pubKey = manager.getPublicKey()
        assertNotNull(pubKey)
        assertTrue(pubKey.encoded.isNotEmpty())
    }

    @Test
    fun `getPublicKeyBytes returns encoded public key`() {
        val bytes = manager.getPublicKeyBytes()
        assertArrayEquals(manager.getPublicKey().encoded, bytes)
    }

    @Test
    fun `getFingerprint returns 8 hex characters`() {
        val fingerprint = manager.getFingerprint()
        assertEquals(8, fingerprint.length)
        assertTrue(
            "Fingerprint should be hex: $fingerprint",
            fingerprint.matches(Regex("[0-9a-f]{8}")),
        )
    }

    @Test
    fun `getFingerprint is deterministic for same key`() {
        val fp1 = manager.getFingerprint()
        val fp2 = manager.getFingerprint()
        assertEquals(fp1, fp2)
    }

    @Test
    fun `different keys produce different fingerprints`() {
        val fp1 = manager.getFingerprint()
        manager.reset()
        val fp2 = manager.getFingerprint()
        // Extremely unlikely to collide with 4 bytes
        assertTrue(
            "Different keys should have different fingerprints",
            fp1 != fp2,
        )
    }

    @Test
    fun `reset clears the cached keypair`() {
        val first = manager.getOrCreateKeyPair()
        manager.reset()
        val second = manager.getOrCreateKeyPair()
        assertTrue(
            "Reset should generate new key",
            !first.public.encoded.contentEquals(second.public.encoded),
        )
    }

    @Test
    fun `keypair persists across manager instances with same storage`() {
        val storage = InMemoryKeyStorage()
        val manager1 = IdentityManager(storage)
        val key1 = manager1.getOrCreateKeyPair()

        val manager2 = IdentityManager(storage)
        val key2 = manager2.getOrCreateKeyPair()

        assertArrayEquals(key1.public.encoded, key2.public.encoded)
    }

    @Test
    fun `generateKeyPair produces EdDSA keys`() {
        val keyPair = IdentityManager.generateKeyPair()
        assertTrue(
            "Expected EdDSA or Ed25519, got: ${keyPair.public.algorithm}",
            keyPair.public.algorithm == "EdDSA" ||
                keyPair.public.algorithm == "Ed25519",
        )
    }

    @Test
    fun `static getFingerprint works with any public key`() {
        val keyPair = IdentityManager.generateKeyPair()
        val fp = IdentityManager.getFingerprint(keyPair.public)
        assertEquals(8, fp.length)
        assertTrue(fp.matches(Regex("[0-9a-f]{8}")))
    }

    @Test
    fun `InMemoryKeyStorage starts with no keypair`() {
        val storage = InMemoryKeyStorage()
        assertEquals(null, storage.loadKeyPair())
    }

    @Test
    fun `InMemoryKeyStorage roundtrips keypair`() {
        val storage = InMemoryKeyStorage()
        val keyPair = IdentityManager.generateKeyPair()
        storage.saveKeyPair(keyPair)
        val loaded = storage.loadKeyPair()
        assertNotNull(loaded)
        assertArrayEquals(keyPair.public.encoded, loaded!!.public.encoded)
    }

    @Test
    fun `InMemoryKeyStorage deleteKeyPair clears storage`() {
        val storage = InMemoryKeyStorage()
        storage.saveKeyPair(IdentityManager.generateKeyPair())
        storage.deleteKeyPair()
        assertEquals(null, storage.loadKeyPair())
    }
}
