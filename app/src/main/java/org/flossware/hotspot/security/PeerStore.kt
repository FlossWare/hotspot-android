package org.flossware.hotspot.security

import java.util.concurrent.ConcurrentHashMap

/**
 * A trusted peer that has completed the pairing protocol.
 *
 * @property publicKeyBase64 Base64-encoded X.509 public key
 * @property label           Human-readable label chosen during pairing
 * @property pairedTimestamp  Epoch millis when pairing completed
 * @property lastSeen        Epoch millis of the most recent connection
 */
data class TrustedPeer(
    val publicKeyBase64: String,
    val label: String,
    val pairedTimestamp: Long,
    val lastSeen: Long = pairedTimestamp,
)

/** Abstraction over peer persistence (SharedPreferences on Android, HashMap in tests). */
interface PeerStorage {
    fun loadPeers(): Map<String, TrustedPeer>
    fun savePeers(peers: Map<String, TrustedPeer>)
}

/** In-memory implementation used in unit tests. */
class InMemoryPeerStorage : PeerStorage {
    private var stored = emptyMap<String, TrustedPeer>()
    override fun loadPeers(): Map<String, TrustedPeer> = stored
    override fun savePeers(peers: Map<String, TrustedPeer>) {
        stored = peers.toMap()
    }
}

/**
 * Thread-safe store for trusted peers, backed by a pluggable [PeerStorage].
 *
 * Peers are keyed by their Base64-encoded public key, which is unique per device.
 */
class PeerStore(
    private val storage: PeerStorage = InMemoryPeerStorage(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val peers = ConcurrentHashMap<String, TrustedPeer>()

    init {
        peers.putAll(storage.loadPeers())
    }

    /** Adds (or replaces) a trusted peer. Returns the new [TrustedPeer]. */
    fun addPeer(publicKeyBase64: String, label: String): TrustedPeer {
        val now = clock()
        val peer = TrustedPeer(
            publicKeyBase64 = publicKeyBase64,
            label = label,
            pairedTimestamp = now,
            lastSeen = now,
        )
        peers[publicKeyBase64] = peer
        persist()
        return peer
    }

    /** Removes a peer. Returns true if the peer existed. */
    fun removePeer(publicKeyBase64: String): Boolean {
        val removed = peers.remove(publicKeyBase64) != null
        if (removed) persist()
        return removed
    }

    /** Returns the peer for the given key, or null if not trusted. */
    fun getPeer(publicKeyBase64: String): TrustedPeer? = peers[publicKeyBase64]

    /** Returns true if the given public key is in the trusted set. */
    fun isTrusted(publicKeyBase64: String): Boolean = peers.containsKey(publicKeyBase64)

    /** Returns a snapshot of all trusted peers. */
    fun listPeers(): List<TrustedPeer> = peers.values.toList()

    /** Returns the number of trusted peers. */
    val size: Int get() = peers.size

    /** Updates the lastSeen timestamp for the given peer. */
    fun updateLastSeen(publicKeyBase64: String) {
        peers.computeIfPresent(publicKeyBase64) { _, peer ->
            peer.copy(lastSeen = clock())
        }
        persist()
    }

    /** Removes all trusted peers. */
    fun clear() {
        peers.clear()
        persist()
    }

    private fun persist() {
        storage.savePeers(peers.toMap())
    }
}
