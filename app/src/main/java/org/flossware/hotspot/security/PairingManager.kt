package org.flossware.hotspot.security

import android.util.Log
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Data extracted from a scanned pairing QR code.
 *
 * @property publicKeyBase64 Base64-encoded host public key
 * @property host            IP address or hostname of the host device
 * @property port            SOCKS5 proxy port on the host
 */
data class PairingInfo(
    val publicKeyBase64: String,
    val host: String,
    val port: Int,
)

/**
 * Manages the pairing flow between host and client devices.
 *
 * Supports two verification methods:
 * - **QR code**: host displays a QR containing its public key and endpoint;
 *   client scans it, then both verify a Short Authentication String (SAS).
 * - **SAS-only**: both devices display a 6-digit code derived from their
 *   public keys; the user confirms they match.
 *
 * Rate-limits failed pairing attempts to [MAX_FAILED_ATTEMPTS] per
 * [RATE_LIMIT_WINDOW_MS] per client identifier.
 */
class PairingManager(
    private val identityManager: IdentityManager,
    private val peerStore: PeerStore,
) {
    private val failedAttempts =
        ConcurrentHashMap<String, MutableList<Long>>()

    /**
     * Generates the content string to be encoded into a pairing QR code.
     * Format: `HOTSPOT-PAIR:<pubkey_b64>:<host>:<port>`
     */
    fun generatePairingQrContent(host: String, port: Int): String {
        val pubKeyBase64 = Base64.getEncoder()
            .encodeToString(identityManager.getPublicKeyBytes())
        return "$QR_PREFIX$pubKeyBase64$QR_SEP$host$QR_SEP$port"
    }

    /**
     * Parses a scanned QR string back into [PairingInfo].
     * Returns null if the format is invalid.
     */
    fun parsePairingQrContent(content: String): PairingInfo? {
        if (!content.startsWith(QR_PREFIX)) return null
        val parts = content.removePrefix(QR_PREFIX).split(QR_SEP)
        if (parts.size < MIN_QR_PARTS) return null
        val port = parts[2].toIntOrNull() ?: return null
        return PairingInfo(
            publicKeyBase64 = parts[0],
            host = parts[1],
            port = port,
        )
    }

    /**
     * Generates a 6-digit Short Authentication String from two public keys.
     *
     * Both parties compute the same SAS regardless of call order (keys are
     * sorted before hashing) and verify the code out-of-band (e.g. read
     * aloud over Bluetooth or displayed side-by-side).
     */
    fun generateSas(
        localPubKey: ByteArray,
        remotePubKey: ByteArray,
    ): String {
        val digest = MessageDigest.getInstance(HASH_ALGORITHM)
        val (first, second) = orderKeys(localPubKey, remotePubKey)
        digest.update(first)
        digest.update(second)
        val hash = digest.digest()

        val value = ((hash[0].toLong() and BYTE_MASK) shl SHORT_SHIFT) or
            ((hash[1].toLong() and BYTE_MASK) shl BYTE_SHIFT) or
            (hash[2].toLong() and BYTE_MASK)
        val code = (value % SAS_MODULUS).toInt()
        return code.toString().padStart(SAS_DIGITS, '0')
    }

    /**
     * Returns true if the client identified by [clientId] is within the
     * rate limit and may attempt pairing. Call [recordFailedAttempt]
     * after a failed verification.
     */
    fun checkRateLimit(clientId: String): Boolean {
        val now = System.currentTimeMillis()
        val attempts = failedAttempts.getOrPut(clientId) { mutableListOf() }
        synchronized(attempts) {
            attempts.removeAll { now - it > RATE_LIMIT_WINDOW_MS }
            return attempts.size < MAX_FAILED_ATTEMPTS
        }
    }

    /** Records a failed pairing attempt for rate-limiting. */
    fun recordFailedAttempt(clientId: String) {
        val attempts = failedAttempts.getOrPut(clientId) { mutableListOf() }
        synchronized(attempts) {
            attempts.add(System.currentTimeMillis())
        }
        Log.d(TAG, "Failed pairing attempt from $clientId")
    }

    /**
     * Completes pairing by adding the remote device as a trusted peer.
     * Returns the new [TrustedPeer].
     */
    fun completePairing(
        remotePubKeyBase64: String,
        label: String,
    ): TrustedPeer {
        Log.i(TAG, "Pairing completed with $label")
        return peerStore.addPeer(remotePubKeyBase64, label)
    }

    // -- internals --

    private fun orderKeys(
        a: ByteArray,
        b: ByteArray,
    ): Pair<ByteArray, ByteArray> {
        return if (compareKeys(a, b) <= 0) a to b else b to a
    }

    companion object {
        private const val TAG = "PairingManager"
        internal const val MAX_FAILED_ATTEMPTS = 5
        internal const val RATE_LIMIT_WINDOW_MS = 60_000L
        private const val SAS_DIGITS = 6
        private const val SAS_MODULUS = 1_000_000L
        private const val HASH_ALGORITHM = "SHA-256"
        private const val QR_PREFIX = "HOTSPOT-PAIR:"
        private const val QR_SEP = ":"
        private const val MIN_QR_PARTS = 3
        private const val BYTE_MASK = 0xFFL
        private const val SHORT_SHIFT = 16
        private const val BYTE_SHIFT = 8

        /**
         * Lexicographic comparison of two byte arrays treated as unsigned.
         */
        internal fun compareKeys(a: ByteArray, b: ByteArray): Int {
            for (i in 0 until minOf(a.size, b.size)) {
                val cmp = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                if (cmp != 0) return cmp
            }
            return a.size - b.size
        }
    }
}
