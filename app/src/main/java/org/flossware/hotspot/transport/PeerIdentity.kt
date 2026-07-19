package org.flossware.hotspot.transport

/**
 * Identifies a remote FlossWare peer.
 *
 * @property publicKeyFingerprint SHA-256 fingerprint of the peer's public key.
 * @property label Human-readable label for the peer (e.g. device name).
 * @property endpoints Map of [TransportType] to transport-specific address string
 *   (e.g. IP address for Wi-Fi Direct, MAC address for Bluetooth).
 * @property protocolVersion Protocol version advertised by the peer.
 */
data class PeerIdentity(
    val publicKeyFingerprint: String,
    val label: String,
    val endpoints: Map<TransportType, String> = emptyMap(),
    val protocolVersion: Int = 1,
)
