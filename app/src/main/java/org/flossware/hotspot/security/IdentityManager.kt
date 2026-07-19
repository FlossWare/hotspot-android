package org.flossware.hotspot.security

import android.util.Log
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey

/**
 * Manages an Ed25519 identity keypair for device authentication.
 *
 * On API 33+ the key lives in Android Keystore (hardware-backed when available).
 * On lower APIs the encoded private key is stored via the injected [KeyStorage].
 * In tests an [InMemoryKeyStorage] avoids any Android dependency.
 */
class IdentityManager(
    private val storage: KeyStorage = InMemoryKeyStorage(),
) {
    private var cachedKeyPair: KeyPair? = null

    /**
     * Returns the existing identity keypair, generating one on first call.
     * Thread-safe: concurrent callers may both generate, but only one wins
     * the store-and-cache race.
     */
    @Synchronized
    fun getOrCreateKeyPair(): KeyPair {
        cachedKeyPair?.let { return it }

        val stored = storage.loadKeyPair()
        if (stored != null) {
            cachedKeyPair = stored
            return stored
        }

        val keyPair = generateKeyPair()
        storage.saveKeyPair(keyPair)
        cachedKeyPair = keyPair
        Log.d(TAG, "Generated new identity keypair: ${getFingerprint(keyPair.public)}")
        return keyPair
    }

    /** Returns the identity public key, creating the keypair if needed. */
    fun getPublicKey(): PublicKey = getOrCreateKeyPair().public

    /** Returns the X.509-encoded form of the identity public key. */
    fun getPublicKeyBytes(): ByteArray = getPublicKey().encoded

    /**
     * Returns a short human-readable fingerprint (last 8 hex chars of
     * SHA-256 of the public key's encoded form).
     */
    fun getFingerprint(): String = getFingerprint(getPublicKey())

    /** Deletes the stored keypair and clears the cache. */
    @Synchronized
    fun reset() {
        storage.deleteKeyPair()
        cachedKeyPair = null
    }

    companion object {
        private const val TAG = "IdentityManager"
        private const val FINGERPRINT_BYTES = 4
        internal const val KEY_ALGORITHM = "Ed25519"

        internal fun generateKeyPair(): KeyPair {
            val generator = KeyPairGenerator.getInstance(KEY_ALGORITHM)
            return generator.generateKeyPair()
        }

        /** Fingerprint of any public key (last 8 hex chars of SHA-256). */
        fun getFingerprint(pubKey: PublicKey): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(pubKey.encoded)
            return hash.takeLast(FINGERPRINT_BYTES)
                .joinToString("") { "%02x".format(it) }
        }
    }
}

/** Abstraction over keypair persistence so unit tests can avoid Android APIs. */
interface KeyStorage {
    fun loadKeyPair(): KeyPair?
    fun saveKeyPair(keyPair: KeyPair)
    fun deleteKeyPair()
}

/** In-memory implementation for tests and fallback. */
class InMemoryKeyStorage : KeyStorage {
    private var stored: KeyPair? = null
    override fun loadKeyPair(): KeyPair? = stored
    override fun saveKeyPair(keyPair: KeyPair) { stored = keyPair }
    override fun deleteKeyPair() { stored = null }
}
