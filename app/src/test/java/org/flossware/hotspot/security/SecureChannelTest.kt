package org.flossware.hotspot.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer

class SecureChannelTest {

    // -- AEAD encrypt / decrypt --------------------------------------------

    @Test
    fun `aeadEncrypt and aeadDecrypt roundtrip`() {
        val key = ByteArray(SecureChannel.KEY_SIZE) { it.toByte() }
        val nonce = ByteArray(SecureChannel.NONCE_SIZE) { it.toByte() }
        val plaintext = "Hello, SecureChannel!".toByteArray()

        val ciphertext = SecureChannel.aeadEncrypt(key, nonce, plaintext)
        val decrypted = SecureChannel.aeadDecrypt(key, nonce, ciphertext)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `aeadEncrypt produces ciphertext longer than plaintext (tag appended)`() {
        val key = ByteArray(SecureChannel.KEY_SIZE) { it.toByte() }
        val nonce = ByteArray(SecureChannel.NONCE_SIZE)
        val plaintext = "data".toByteArray()

        val ciphertext = SecureChannel.aeadEncrypt(key, nonce, plaintext)
        assertTrue(
            "Ciphertext (${ciphertext.size}) should be longer " +
                "than plaintext (${plaintext.size}) by tag",
            ciphertext.size >= plaintext.size + SecureChannel.TAG_SIZE,
        )
    }

    @Test(expected = Exception::class)
    fun `aeadDecrypt rejects tampered ciphertext`() {
        val key = ByteArray(SecureChannel.KEY_SIZE) { 0xAA.toByte() }
        val nonce = ByteArray(SecureChannel.NONCE_SIZE) { 0xBB.toByte() }
        val plaintext = "secret".toByteArray()

        val ciphertext = SecureChannel.aeadEncrypt(key, nonce, plaintext)
        // Tamper with the middle of the ciphertext
        ciphertext[ciphertext.size / 2] =
            (ciphertext[ciphertext.size / 2].toInt() xor 0xFF).toByte()
        SecureChannel.aeadDecrypt(key, nonce, ciphertext)
    }

    @Test(expected = Exception::class)
    fun `aeadDecrypt rejects wrong key`() {
        val key1 = ByteArray(SecureChannel.KEY_SIZE) { 0x01 }
        val key2 = ByteArray(SecureChannel.KEY_SIZE) { 0x02 }
        val nonce = ByteArray(SecureChannel.NONCE_SIZE)
        val ciphertext = SecureChannel.aeadEncrypt(key1, nonce, "data".toByteArray())
        SecureChannel.aeadDecrypt(key2, nonce, ciphertext)
    }

    // -- Channel encrypt / decrypt -----------------------------------------

    @Test
    fun `encrypt and decrypt roundtrip via fromSharedSecret`() {
        val secret = ByteArray(SecureChannel.KEY_SIZE) { it.toByte() }
        val remotePub = ByteArray(SecureChannel.KEY_SIZE) { 0xFF.toByte() }

        val sender = SecureChannel.fromSharedSecret(secret, remotePub, isInitiator = true)
        val receiver = SecureChannel.fromSharedSecret(secret, remotePub, isInitiator = false)

        val plaintext = "Hello from initiator".toByteArray()
        val frame = sender.encrypt(plaintext)
        val decrypted = receiver.decrypt(frame)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `bidirectional communication works`() {
        val secret = ByteArray(SecureChannel.KEY_SIZE) { (it * 3).toByte() }
        val remotePub = ByteArray(SecureChannel.KEY_SIZE)

        val client = SecureChannel.fromSharedSecret(secret, remotePub, isInitiator = true)
        val server = SecureChannel.fromSharedSecret(secret, remotePub, isInitiator = false)

        // Client -> Server
        val msg1 = "Request".toByteArray()
        val frame1 = client.encrypt(msg1)
        assertArrayEquals(msg1, server.decrypt(frame1))

        // Server -> Client
        val msg2 = "Response".toByteArray()
        val frame2 = server.encrypt(msg2)
        assertArrayEquals(msg2, client.decrypt(frame2))
    }

    @Test
    fun `multiple sequential messages maintain correct nonce sequence`() {
        val secret = ByteArray(SecureChannel.KEY_SIZE) { 0x42 }
        val remotePub = ByteArray(SecureChannel.KEY_SIZE)

        val sender = SecureChannel.fromSharedSecret(secret, remotePub, isInitiator = true)
        val receiver = SecureChannel.fromSharedSecret(secret, remotePub, isInitiator = false)

        for (i in 0 until 10) {
            val msg = "Message $i".toByteArray()
            val frame = sender.encrypt(msg)
            val decrypted = receiver.decrypt(frame)
            assertArrayEquals("Message $i failed", msg, decrypted)
        }
    }

    // -- Replay detection --------------------------------------------------

    @Test(expected = SecurityException::class)
    fun `decrypt rejects replayed frame (duplicate nonce)`() {
        val secret = ByteArray(SecureChannel.KEY_SIZE) { 0x11 }
        val remotePub = ByteArray(SecureChannel.KEY_SIZE)

        val sender = SecureChannel.fromSharedSecret(secret, remotePub, isInitiator = true)
        val receiver = SecureChannel.fromSharedSecret(secret, remotePub, isInitiator = false)

        val frame = sender.encrypt("original".toByteArray())
        receiver.decrypt(frame) // First time: OK
        receiver.decrypt(frame) // Replay: should throw
    }

    @Test(expected = SecurityException::class)
    fun `decrypt rejects out-of-order frame`() {
        val secret = ByteArray(SecureChannel.KEY_SIZE) { 0x22 }
        val remotePub = ByteArray(SecureChannel.KEY_SIZE)

        val sender = SecureChannel.fromSharedSecret(secret, remotePub, isInitiator = true)
        val receiver = SecureChannel.fromSharedSecret(secret, remotePub, isInitiator = false)

        val frame0 = sender.encrypt("msg0".toByteArray())
        val frame1 = sender.encrypt("msg1".toByteArray())

        receiver.decrypt(frame1) // Skip frame0, accept frame1
        receiver.decrypt(frame0) // frame0 nonce < expected: reject
    }

    // -- Frame format ------------------------------------------------------

    @Test
    fun `encrypted frame starts with 4-byte length header`() {
        val secret = ByteArray(SecureChannel.KEY_SIZE) { 0x33 }
        val remotePub = ByteArray(SecureChannel.KEY_SIZE)
        val sender = SecureChannel.fromSharedSecret(secret, remotePub, isInitiator = true)

        val frame = sender.encrypt("test".toByteArray())
        val headerLen = ByteBuffer.wrap(frame, 0, SecureChannel.HEADER_SIZE).int
        assertEquals(
            "Header length should match payload",
            frame.size - SecureChannel.HEADER_SIZE, headerLen,
        )
    }

    @Test
    fun `frame contains nonce after header`() {
        val secret = ByteArray(SecureChannel.KEY_SIZE) { 0x44 }
        val remotePub = ByteArray(SecureChannel.KEY_SIZE)
        val sender = SecureChannel.fromSharedSecret(secret, remotePub, isInitiator = true)

        val frame = sender.encrypt("data".toByteArray())
        // First frame nonce should be counter=0 (first 4 bytes zero, last 8 zero)
        val nonce = frame.copyOfRange(
            SecureChannel.HEADER_SIZE,
            SecureChannel.HEADER_SIZE + SecureChannel.NONCE_SIZE,
        )
        val counter = ByteBuffer.wrap(nonce, 4, 8).long
        assertEquals("First nonce counter should be 0", 0L, counter)
    }

    @Test
    fun `second frame has nonce counter 1`() {
        val secret = ByteArray(SecureChannel.KEY_SIZE) { 0x55 }
        val remotePub = ByteArray(SecureChannel.KEY_SIZE)
        val sender = SecureChannel.fromSharedSecret(secret, remotePub, isInitiator = true)

        sender.encrypt("first".toByteArray())
        val frame2 = sender.encrypt("second".toByteArray())
        val nonce = frame2.copyOfRange(
            SecureChannel.HEADER_SIZE,
            SecureChannel.HEADER_SIZE + SecureChannel.NONCE_SIZE,
        )
        val counter = ByteBuffer.wrap(nonce, 4, 8).long
        assertEquals("Second nonce counter should be 1", 1L, counter)
    }

    // -- Stream read/write -------------------------------------------------

    @Test
    fun `writeEncrypted and readDecrypted roundtrip via streams`() {
        val secret = ByteArray(SecureChannel.KEY_SIZE) { 0x66 }
        val remotePub = ByteArray(SecureChannel.KEY_SIZE)

        val sender = SecureChannel.fromSharedSecret(secret, remotePub, isInitiator = true)
        val receiver = SecureChannel.fromSharedSecret(secret, remotePub, isInitiator = false)

        val buffer = ByteArrayOutputStream()
        val plaintext = "Stream roundtrip test".toByteArray()
        sender.writeEncrypted(buffer, plaintext)

        val input = ByteArrayInputStream(buffer.toByteArray())
        val decrypted = receiver.readDecrypted(input)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `readDecrypted handles multiple frames in a stream`() {
        val secret = ByteArray(SecureChannel.KEY_SIZE) { 0x77 }
        val remotePub = ByteArray(SecureChannel.KEY_SIZE)

        val sender = SecureChannel.fromSharedSecret(secret, remotePub, isInitiator = true)
        val receiver = SecureChannel.fromSharedSecret(secret, remotePub, isInitiator = false)

        val buffer = ByteArrayOutputStream()
        sender.writeEncrypted(buffer, "frame1".toByteArray())
        sender.writeEncrypted(buffer, "frame2".toByteArray())
        sender.writeEncrypted(buffer, "frame3".toByteArray())

        val input = ByteArrayInputStream(buffer.toByteArray())
        assertArrayEquals("frame1".toByteArray(), receiver.readDecrypted(input))
        assertArrayEquals("frame2".toByteArray(), receiver.readDecrypted(input))
        assertArrayEquals("frame3".toByteArray(), receiver.readDecrypted(input))
    }

    @Test(expected = SecurityException::class)
    fun `readDecrypted rejects negative frame length`() {
        val secret = ByteArray(SecureChannel.KEY_SIZE) { 0x88.toByte() }
        val remotePub = ByteArray(SecureChannel.KEY_SIZE)
        val receiver = SecureChannel.fromSharedSecret(secret, remotePub, isInitiator = false)

        // Craft a frame with negative length
        val header = ByteBuffer.allocate(4).putInt(-1).array()
        val input = ByteArrayInputStream(header)
        receiver.readDecrypted(input)
    }

    @Test(expected = SecurityException::class)
    fun `readDecrypted rejects oversized frame length`() {
        val secret = ByteArray(SecureChannel.KEY_SIZE) { 0x99.toByte() }
        val remotePub = ByteArray(SecureChannel.KEY_SIZE)
        val receiver = SecureChannel.fromSharedSecret(secret, remotePub, isInitiator = false)

        val header = ByteBuffer.allocate(4)
            .putInt(SecureChannel.MAX_FRAME + 1).array()
        val input = ByteArrayInputStream(header)
        receiver.readDecrypted(input)
    }

    // -- HKDF --------------------------------------------------------------

    @Test
    fun `hkdfExpand produces deterministic output`() {
        val ikm = ByteArray(SecureChannel.KEY_SIZE) { 0xCC.toByte() }
        val info = "test-info".toByteArray()
        val out1 = SecureChannel.hkdfExpand(ikm, info, 64)
        val out2 = SecureChannel.hkdfExpand(ikm, info, 64)
        assertArrayEquals(out1, out2)
    }

    @Test
    fun `hkdfExpand produces correct length`() {
        val ikm = ByteArray(SecureChannel.KEY_SIZE) { 0xDD.toByte() }
        assertEquals(
            16, SecureChannel.hkdfExpand(ikm, "a".toByteArray(), 16).size,
        )
        assertEquals(
            64, SecureChannel.hkdfExpand(ikm, "b".toByteArray(), 64).size,
        )
        assertEquals(
            SecureChannel.KEY_SIZE * 3,
            SecureChannel.hkdfExpand(
                ikm, "c".toByteArray(), SecureChannel.KEY_SIZE * 3,
            ).size,
        )
    }

    @Test
    fun `hkdfExpand with different info produces different output`() {
        val ikm = ByteArray(SecureChannel.KEY_SIZE) { 0xEE.toByte() }
        val out1 = SecureChannel.hkdfExpand(ikm, "info-1".toByteArray(), 32)
        val out2 = SecureChannel.hkdfExpand(ikm, "info-2".toByteArray(), 32)
        assertTrue(
            "Different info should produce different output",
            !out1.contentEquals(out2),
        )
    }

    // -- Handshake (loopback) ----------------------------------------------

    @Test
    fun `handshake loopback produces working channel pair`() {
        val clientIdentity = IdentityManager.generateKeyPair()
        val serverIdentity = IdentityManager.generateKeyPair()

        // Piped streams for bidirectional communication
        val clientToServer = PipedOutputStream()
        val serverReadsClient = PipedInputStream(clientToServer)
        val serverToClient = PipedOutputStream()
        val clientReadsServer = PipedInputStream(serverToClient)

        var clientChannel: SecureChannel? = null
        var serverChannel: SecureChannel? = null

        val clientThread = Thread {
            clientChannel = SecureChannel.handshakeAsInitiator(
                input = clientReadsServer,
                output = clientToServer,
                localStaticPubKey = clientIdentity.public.encoded,
            )
        }
        val serverThread = Thread {
            serverChannel = SecureChannel.handshakeAsResponder(
                input = serverReadsClient,
                output = serverToClient,
                localStaticPubKey = serverIdentity.public.encoded,
            )
        }

        clientThread.start()
        serverThread.start()
        clientThread.join(5000)
        serverThread.join(5000)

        val client = clientChannel!!
        val server = serverChannel!!

        // Client -> Server
        val request = "Hello, server!".toByteArray()
        val encRequest = client.encrypt(request)
        assertArrayEquals(request, server.decrypt(encRequest))

        // Server -> Client
        val response = "Hello, client!".toByteArray()
        val encResponse = server.encrypt(response)
        assertArrayEquals(response, client.decrypt(encResponse))

        // Verify static keys were exchanged
        assertArrayEquals(
            serverIdentity.public.encoded,
            client.remoteStaticPubKey,
        )
        assertArrayEquals(
            clientIdentity.public.encoded,
            server.remoteStaticPubKey,
        )
    }

    @Test
    fun `handshake produces unique session keys each time`() {
        val clientId = IdentityManager.generateKeyPair()
        val serverId = IdentityManager.generateKeyPair()

        fun doHandshake(): Pair<SecureChannel, SecureChannel> {
            val c2s = PipedOutputStream()
            val sRc = PipedInputStream(c2s)
            val s2c = PipedOutputStream()
            val cRs = PipedInputStream(s2c)

            var cc: SecureChannel? = null
            var sc: SecureChannel? = null
            val ct = Thread {
                cc = SecureChannel.handshakeAsInitiator(
                    cRs, c2s, clientId.public.encoded,
                )
            }
            val st = Thread {
                sc = SecureChannel.handshakeAsResponder(
                    sRc, s2c, serverId.public.encoded,
                )
            }
            ct.start(); st.start()
            ct.join(5000); st.join(5000)
            return cc!! to sc!!
        }

        val (c1, s1) = doHandshake()
        val (c2, s2) = doHandshake()

        // Each handshake uses different ephemeral keys, so the encrypted
        // output for the same plaintext should differ.
        val frame1 = c1.encrypt("same".toByteArray())
        val frame2 = c2.encrypt("same".toByteArray())
        assertTrue(
            "Different sessions should produce different ciphertext",
            !frame1.contentEquals(frame2),
        )
    }

    // -- Large data --------------------------------------------------------

    @Test
    fun `encrypt and decrypt handles large payloads`() {
        val secret = ByteArray(SecureChannel.KEY_SIZE) { 0xAB.toByte() }
        val remotePub = ByteArray(SecureChannel.KEY_SIZE)

        val sender = SecureChannel.fromSharedSecret(secret, remotePub, isInitiator = true)
        val receiver = SecureChannel.fromSharedSecret(secret, remotePub, isInitiator = false)

        val large = ByteArray(65536) { (it % 256).toByte() }
        val frame = sender.encrypt(large)
        val decrypted = receiver.decrypt(frame)
        assertArrayEquals(large, decrypted)
    }

    // -- readFully ---------------------------------------------------------

    @Test
    fun `readFully reads exact number of bytes`() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val input = ByteArrayInputStream(data)
        val result = SecureChannel.readFully(input, 3)
        assertArrayEquals(byteArrayOf(1, 2, 3), result)
    }

    @Test(expected = java.io.IOException::class)
    fun `readFully throws on premature EOF`() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2))
        SecureChannel.readFully(input, 10)
    }

    // -- Empty plaintext ---------------------------------------------------

    @Test
    fun `encrypt and decrypt handles empty plaintext`() {
        val secret = ByteArray(SecureChannel.KEY_SIZE) { 0xBC.toByte() }
        val remotePub = ByteArray(SecureChannel.KEY_SIZE)

        val sender = SecureChannel.fromSharedSecret(secret, remotePub, isInitiator = true)
        val receiver = SecureChannel.fromSharedSecret(secret, remotePub, isInitiator = false)

        val frame = sender.encrypt(ByteArray(0))
        val decrypted = receiver.decrypt(frame)
        assertEquals(0, decrypted.size)
    }
}
