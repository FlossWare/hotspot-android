package org.flossware.hotspot.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PairingManagerTest {

    private lateinit var identityManager: IdentityManager
    private lateinit var peerStore: PeerStore
    private lateinit var pairingManager: PairingManager

    @Before
    fun setUp() {
        identityManager = IdentityManager(InMemoryKeyStorage())
        peerStore = PeerStore(InMemoryPeerStorage())
        pairingManager = PairingManager(identityManager, peerStore)
    }

    // -- QR code content ---------------------------------------------------

    @Test
    fun `generatePairingQrContent produces correct format`() {
        val content = pairingManager.generatePairingQrContent(
            "192.168.49.1", 1080,
        )
        assertTrue(
            "Should start with HOTSPOT-PAIR:",
            content.startsWith("HOTSPOT-PAIR:"),
        )
        assertTrue(content.contains("192.168.49.1"))
        assertTrue(content.contains("1080"))
    }

    @Test
    fun `parsePairingQrContent roundtrips with generate`() {
        val content = pairingManager.generatePairingQrContent(
            "192.168.49.1", 1080,
        )
        val info = pairingManager.parsePairingQrContent(content)
        assertNotNull(info)
        assertEquals("192.168.49.1", info!!.host)
        assertEquals(1080, info.port)
        assertTrue(info.publicKeyBase64.isNotEmpty())
    }

    @Test
    fun `parsePairingQrContent returns null for invalid prefix`() {
        assertNull(pairingManager.parsePairingQrContent("INVALID:data"))
    }

    @Test
    fun `parsePairingQrContent returns null for too few parts`() {
        assertNull(pairingManager.parsePairingQrContent("HOTSPOT-PAIR:key"))
    }

    @Test
    fun `parsePairingQrContent returns null for non-integer port`() {
        assertNull(
            pairingManager.parsePairingQrContent(
                "HOTSPOT-PAIR:key:host:notaport",
            ),
        )
    }

    @Test
    fun `parsePairingQrContent returns null for empty string`() {
        assertNull(pairingManager.parsePairingQrContent(""))
    }

    // -- SAS generation ----------------------------------------------------

    @Test
    fun `generateSas produces 6-digit string`() {
        val keyA = ByteArray(32) { 0x01 }
        val keyB = ByteArray(32) { 0x02 }
        val sas = pairingManager.generateSas(keyA, keyB)
        assertEquals(6, sas.length)
        assertTrue(
            "SAS should be digits: $sas",
            sas.matches(Regex("\\d{6}")),
        )
    }

    @Test
    fun `generateSas is commutative`() {
        val keyA = ByteArray(32) { (it % 256).toByte() }
        val keyB = ByteArray(32) { ((it + 128) % 256).toByte() }
        val sas1 = pairingManager.generateSas(keyA, keyB)
        val sas2 = pairingManager.generateSas(keyB, keyA)
        assertEquals(
            "SAS must be the same regardless of argument order",
            sas1, sas2,
        )
    }

    @Test
    fun `generateSas differs for different keys`() {
        val keyA = ByteArray(32) { 0x01 }
        val keyB = ByteArray(32) { 0x02 }
        val keyC = ByteArray(32) { 0x03 }
        val sas1 = pairingManager.generateSas(keyA, keyB)
        val sas2 = pairingManager.generateSas(keyA, keyC)
        assertTrue(
            "Different key pairs should produce different SAS",
            sas1 != sas2,
        )
    }

    @Test
    fun `generateSas is deterministic`() {
        val keyA = ByteArray(32) { 0xAA.toByte() }
        val keyB = ByteArray(32) { 0xBB.toByte() }
        val sas1 = pairingManager.generateSas(keyA, keyB)
        val sas2 = pairingManager.generateSas(keyA, keyB)
        assertEquals(sas1, sas2)
    }

    @Test
    fun `generateSas pads with leading zeros`() {
        // Use keys that produce a SAS with value < 100000 (leading zeros)
        // This is probabilistic, but we test the padding logic
        val sas = pairingManager.generateSas(ByteArray(32), ByteArray(32))
        assertEquals(6, sas.length)
    }

    // -- Rate limiting -----------------------------------------------------

    @Test
    fun `checkRateLimit allows first attempt`() {
        assertTrue(pairingManager.checkRateLimit("client-1"))
    }

    @Test
    fun `checkRateLimit allows up to MAX_FAILED_ATTEMPTS`() {
        for (ignored in 0 until PairingManager.MAX_FAILED_ATTEMPTS - 1) {
            pairingManager.recordFailedAttempt("client-1")
        }
        assertTrue(pairingManager.checkRateLimit("client-1"))
    }

    @Test
    fun `checkRateLimit blocks after MAX_FAILED_ATTEMPTS`() {
        for (ignored in 0 until PairingManager.MAX_FAILED_ATTEMPTS) {
            pairingManager.recordFailedAttempt("client-1")
        }
        assertFalse(pairingManager.checkRateLimit("client-1"))
    }

    @Test
    fun `checkRateLimit is per-client`() {
        for (ignored in 0 until PairingManager.MAX_FAILED_ATTEMPTS) {
            pairingManager.recordFailedAttempt("client-1")
        }
        // Different client should still be allowed
        assertTrue(pairingManager.checkRateLimit("client-2"))
    }

    // -- Pairing completion ------------------------------------------------

    @Test
    fun `completePairing adds peer to store`() {
        pairingManager.completePairing("base64key", "Bob's Tablet")
        assertTrue(peerStore.isTrusted("base64key"))
        assertEquals("Bob's Tablet", peerStore.getPeer("base64key")!!.label)
    }

    // -- Key comparison ----------------------------------------------------

    @Test
    fun `compareKeys equal arrays return 0`() {
        val a = byteArrayOf(1, 2, 3)
        assertEquals(0, PairingManager.compareKeys(a, a.clone()))
    }

    @Test
    fun `compareKeys smaller first byte returns negative`() {
        assertTrue(
            PairingManager.compareKeys(
                byteArrayOf(0, 2, 3),
                byteArrayOf(1, 2, 3),
            ) < 0,
        )
    }

    @Test
    fun `compareKeys larger first byte returns positive`() {
        assertTrue(
            PairingManager.compareKeys(
                byteArrayOf(2, 0, 0),
                byteArrayOf(1, 0, 0),
            ) > 0,
        )
    }

    @Test
    fun `compareKeys shorter array is smaller when prefix matches`() {
        assertTrue(
            PairingManager.compareKeys(
                byteArrayOf(1, 2),
                byteArrayOf(1, 2, 3),
            ) < 0,
        )
    }

    @Test
    fun `compareKeys treats bytes as unsigned`() {
        // 0xFF unsigned = 255, which is > 0x01
        assertTrue(
            PairingManager.compareKeys(
                byteArrayOf(0xFF.toByte()),
                byteArrayOf(0x01),
            ) > 0,
        )
    }
}
