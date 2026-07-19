package org.flossware.hotspot.security

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted transport channel using X25519 key agreement and
 * ChaCha20-Poly1305 AEAD.
 *
 * ## Handshake
 * 1. Both sides exchange ephemeral X25519 public keys + static identity keys.
 * 2. Both compute the X25519 shared secret from their ephemeral private key
 *    and the remote ephemeral public key.
 * 3. Session keys (one per direction) are derived via HKDF-SHA256.
 *
 * ## Frame format
 * ```
 * [length:4 big-endian][nonce:12][ciphertext+tag:N]
 * ```
 * - `length` covers `nonce + ciphertext + tag`
 * - Nonce is a 96-bit counter (first 4 bytes zero, last 8 bytes = counter)
 * - Tag is the 16-byte Poly1305 authenticator (appended by the AEAD cipher)
 *
 * ## Replay prevention
 * The receiver tracks a monotonically increasing nonce counter and rejects
 * any frame whose nonce is below the expected value.
 *
 * ## Rekeying
 * After [REKEY_BYTES_THRESHOLD] bytes transferred or
 * [REKEY_TIME_MS] elapsed, new keys are derived from a
 * separate rekey secret and the counters are reset.
 */
class SecureChannel private constructor(
    private var sendKey: ByteArray,
    private var recvKey: ByteArray,
    /** The remote peer's static (identity) public key, X.509-encoded. */
    val remoteStaticPubKey: ByteArray,
    private var rekeySecret: ByteArray,
) {
    private var sendNonce: Long = 0
    private var recvNonce: Long = 0
    private var bytesSent: Long = 0
    private var bytesReceived: Long = 0
    private val sessionStartMs: Long = System.currentTimeMillis()
    private var rekeyCount: Int = 0

    private val sendLock = Any()
    private val recvLock = Any()

    // -- Encrypt / Decrypt --------------------------------------------------

    /**
     * Encrypts [plaintext] and returns a self-contained frame
     * (header + nonce + ciphertext + tag).
     */
    fun encrypt(plaintext: ByteArray): ByteArray {
        synchronized(sendLock) {
            maybeRekey()
            val nonceBytes = buildNonce(sendNonce++)
            val ciphertext = aeadEncrypt(sendKey, nonceBytes, plaintext)

            val payloadLen = NONCE_SIZE + ciphertext.size
            val frame = ByteArray(HEADER_SIZE + payloadLen)
            ByteBuffer.wrap(frame).putInt(payloadLen)
            System.arraycopy(nonceBytes, 0, frame, HEADER_SIZE, NONCE_SIZE)
            System.arraycopy(
                ciphertext, 0, frame, HEADER_SIZE + NONCE_SIZE, ciphertext.size,
            )
            bytesSent += frame.size
            return frame
        }
    }

    /**
     * Decrypts a complete frame previously produced by [encrypt].
     *
     * @throws SecurityException on replay (out-of-order nonce) or
     *         authentication failure.
     * @throws IllegalArgumentException if the frame is structurally invalid.
     */
    fun decrypt(frame: ByteArray): ByteArray {
        synchronized(recvLock) {
            val minSize = HEADER_SIZE + NONCE_SIZE + TAG_SIZE
            require(frame.size >= minSize) {
                "Frame too short: ${frame.size} bytes (min $minSize)"
            }
            val payloadLen = ByteBuffer.wrap(frame, 0, HEADER_SIZE).int
            require(frame.size >= HEADER_SIZE + payloadLen) {
                "Incomplete frame"
            }

            val nonceBytes = frame.copyOfRange(
                HEADER_SIZE, HEADER_SIZE + NONCE_SIZE,
            )
            val ciphertext = frame.copyOfRange(
                HEADER_SIZE + NONCE_SIZE, HEADER_SIZE + payloadLen,
            )

            val counter = nonceCounter(nonceBytes)
            if (counter < recvNonce) {
                throw SecurityException(
                    "Replay detected: nonce $counter < expected $recvNonce",
                )
            }
            recvNonce = counter + 1

            val plaintext = aeadDecrypt(recvKey, nonceBytes, ciphertext)
            bytesReceived += frame.size
            return plaintext
        }
    }

    // -- Stream helpers -----------------------------------------------------

    /** Encrypts [plaintext] and writes the frame to [output]. */
    fun writeEncrypted(output: OutputStream, plaintext: ByteArray) {
        output.write(encrypt(plaintext))
        output.flush()
    }

    /** Reads one encrypted frame from [input] and returns the plaintext. */
    fun readDecrypted(input: InputStream): ByteArray {
        val header = readFully(input, HEADER_SIZE)
        val payloadLen = ByteBuffer.wrap(header).int
        if (payloadLen < NONCE_SIZE + TAG_SIZE || payloadLen > MAX_FRAME) {
            throw SecurityException("Invalid frame length: $payloadLen")
        }
        val payload = readFully(input, payloadLen)
        val frame = ByteArray(HEADER_SIZE + payloadLen)
        System.arraycopy(header, 0, frame, 0, HEADER_SIZE)
        System.arraycopy(payload, 0, frame, HEADER_SIZE, payloadLen)
        return decrypt(frame)
    }

    // -- Rekeying -----------------------------------------------------------

    private fun maybeRekey() {
        val overBytes = bytesSent + bytesReceived > REKEY_BYTES_THRESHOLD
        val overTime = System.currentTimeMillis() - sessionStartMs > REKEY_TIME_MS
        if (!overBytes && !overTime) return
        if (rekeySecret.isEmpty()) return

        rekeyCount++
        val fresh = hkdfExpand(
            rekeySecret,
            "rekey-$rekeyCount".toByteArray(),
            KEY_SIZE * 2,
        )
        sendKey = fresh.copyOfRange(0, KEY_SIZE)
        recvKey = fresh.copyOfRange(KEY_SIZE, KEY_SIZE * 2)
        sendNonce = 0
        recvNonce = 0
        bytesSent = 0
        bytesReceived = 0
        Log.d(TAG, "Rekeyed (round $rekeyCount)")
    }

    // -- Companion / factories ----------------------------------------------

    companion object {
        private const val TAG = "SecureChannel"
        internal const val CIPHER_ALGO = "ChaCha20-Poly1305"
        private const val CIPHER_KEY = "ChaCha20"
        private const val KA_ALGO = "XDH"
        private const val KG_ALGO = "X25519"
        private const val HMAC_ALGO = "HmacSHA256"
        private const val HMAC_LEN = 32

        const val KEY_SIZE = 32
        const val NONCE_SIZE = 12
        const val TAG_SIZE = 16
        const val HEADER_SIZE = 4
        internal const val MAX_FRAME = 1024 * 1024
        private const val MAX_KEY_EXCHANGE = 256

        internal const val REKEY_BYTES_THRESHOLD = 1L * 1024 * 1024 * 1024
        internal const val REKEY_TIME_MS = 3_600_000L

        private const val KDF_INFO = "hotspot-secure-channel"
        private const val NONCE_COUNTER_OFFSET = 4
        private const val NONCE_COUNTER_LEN = 8

        // -- Handshake (initiator = client) --------------------------------

        /**
         * Client-side handshake.  Sends its ephemeral + static keys first,
         * then reads the server's.
         */
        fun handshakeAsInitiator(
            input: InputStream,
            output: OutputStream,
            localStaticPubKey: ByteArray,
        ): SecureChannel {
            val eph = generateEphemeralKeyPair()
            writeKeyExchange(output, eph.public.encoded, localStaticPubKey)
            val (remoteEph, remoteStatic) = readKeyExchange(input)
            return deriveChannel(
                eph, remoteEph, remoteStatic, isInitiator = true,
            )
        }

        /**
         * Server-side handshake.  Reads the client's keys first, then sends
         * its own.
         */
        fun handshakeAsResponder(
            input: InputStream,
            output: OutputStream,
            localStaticPubKey: ByteArray,
        ): SecureChannel {
            val (remoteEph, remoteStatic) = readKeyExchange(input)
            val eph = generateEphemeralKeyPair()
            writeKeyExchange(output, eph.public.encoded, localStaticPubKey)
            return deriveChannel(
                eph, remoteEph, remoteStatic, isInitiator = false,
            )
        }

        /**
         * Creates a channel directly from a pre-shared key (useful for tests).
         */
        fun fromSharedSecret(
            sharedSecret: ByteArray,
            remoteStaticPubKey: ByteArray,
            isInitiator: Boolean,
        ): SecureChannel {
            return deriveFromSecret(sharedSecret, remoteStaticPubKey, isInitiator)
        }

        // -- Key exchange wire format --------------------------------------

        private fun writeKeyExchange(
            out: OutputStream,
            ephPub: ByteArray,
            staticPub: ByteArray,
        ) {
            val buf = ByteBuffer.allocate(HEADER_SIZE * 2 + ephPub.size + staticPub.size)
            buf.putInt(ephPub.size)
            buf.put(ephPub)
            buf.putInt(staticPub.size)
            buf.put(staticPub)
            out.write(buf.array())
            out.flush()
        }

        private fun readKeyExchange(
            input: InputStream,
        ): Pair<ByteArray, ByteArray> {
            val ephLen = ByteBuffer.wrap(readFully(input, HEADER_SIZE)).int
            require(ephLen in 1..MAX_KEY_EXCHANGE) {
                "Bad ephemeral key size: $ephLen"
            }
            val ephPub = readFully(input, ephLen)

            val staticLen = ByteBuffer.wrap(readFully(input, HEADER_SIZE)).int
            require(staticLen in 1..MAX_KEY_EXCHANGE) {
                "Bad static key size: $staticLen"
            }
            val staticPub = readFully(input, staticLen)
            return ephPub to staticPub
        }

        // -- Crypto primitives ---------------------------------------------

        private fun deriveChannel(
            localEph: KeyPair,
            remoteEphPubBytes: ByteArray,
            remoteStaticPubBytes: ByteArray,
            isInitiator: Boolean,
        ): SecureChannel {
            val remoteEphPub = decodeX25519Public(remoteEphPubBytes)
            val agreement = KeyAgreement.getInstance(KA_ALGO)
            agreement.init(localEph.private)
            agreement.doPhase(remoteEphPub, true)
            val secret = agreement.generateSecret()
            return deriveFromSecret(secret, remoteStaticPubBytes, isInitiator)
        }

        private fun deriveFromSecret(
            secret: ByteArray,
            remoteStaticPubBytes: ByteArray,
            isInitiator: Boolean,
        ): SecureChannel {
            val material = hkdfExpand(
                secret, KDF_INFO.toByteArray(), KEY_SIZE * 3,
            )
            val clientKey = material.copyOfRange(0, KEY_SIZE)
            val serverKey = material.copyOfRange(KEY_SIZE, KEY_SIZE * 2)
            val rekey = material.copyOfRange(KEY_SIZE * 2, KEY_SIZE * 3)

            return if (isInitiator) {
                SecureChannel(clientKey, serverKey, remoteStaticPubBytes, rekey)
            } else {
                SecureChannel(serverKey, clientKey, remoteStaticPubBytes, rekey)
            }
        }

        internal fun generateEphemeralKeyPair(): KeyPair =
            KeyPairGenerator.getInstance(KG_ALGO).generateKeyPair()

        internal fun decodeX25519Public(encoded: ByteArray): PublicKey =
            KeyFactory.getInstance(KG_ALGO)
                .generatePublic(X509EncodedKeySpec(encoded))

        internal fun aeadEncrypt(
            key: ByteArray,
            nonce: ByteArray,
            plaintext: ByteArray,
        ): ByteArray {
            val cipher = Cipher.getInstance(CIPHER_ALGO)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, CIPHER_KEY),
                IvParameterSpec(nonce),
            )
            return cipher.doFinal(plaintext)
        }

        internal fun aeadDecrypt(
            key: ByteArray,
            nonce: ByteArray,
            ciphertext: ByteArray,
        ): ByteArray {
            val cipher = Cipher.getInstance(CIPHER_ALGO)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, CIPHER_KEY),
                IvParameterSpec(nonce),
            )
            return cipher.doFinal(ciphertext)
        }

        /**
         * HKDF-Expand (RFC 5869) using HMAC-SHA256.
         *
         * [ikm] is used directly as the PRK (acceptable when the input has
         * high entropy, as is the case for X25519 shared secrets).
         */
        internal fun hkdfExpand(
            ikm: ByteArray,
            info: ByteArray,
            length: Int,
        ): ByteArray {
            val n = (length + HMAC_LEN - 1) / HMAC_LEN
            val result = ByteArray(n * HMAC_LEN)
            var prev = ByteArray(0)
            val mac = Mac.getInstance(HMAC_ALGO)
            mac.init(SecretKeySpec(ikm, HMAC_ALGO))

            for (i in 1..n) {
                mac.reset()
                mac.update(prev)
                mac.update(info)
                mac.update(i.toByte())
                prev = mac.doFinal()
                System.arraycopy(
                    prev, 0, result, (i - 1) * HMAC_LEN, HMAC_LEN,
                )
            }
            return result.copyOfRange(0, length)
        }

        private fun buildNonce(counter: Long): ByteArray {
            val nonce = ByteArray(NONCE_SIZE)
            ByteBuffer.wrap(nonce, NONCE_COUNTER_OFFSET, NONCE_COUNTER_LEN)
                .putLong(counter)
            return nonce
        }

        private fun nonceCounter(nonce: ByteArray): Long =
            ByteBuffer.wrap(nonce, NONCE_COUNTER_OFFSET, NONCE_COUNTER_LEN)
                .long

        internal fun readFully(input: InputStream, size: Int): ByteArray {
            val buf = ByteArray(size)
            var off = 0
            while (off < size) {
                val n = input.read(buf, off, size - off)
                if (n == -1) {
                    throw IOException(
                        "Stream closed after $off of $size bytes",
                    )
                }
                off += n
            }
            return buf
        }
    }
}
