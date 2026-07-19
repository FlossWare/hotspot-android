package org.flossware.hotspot.discovery

import org.flossware.hotspot.transport.PeerIdentity
import org.flossware.hotspot.transport.TransportType

/**
 * A single service discovery result representing a nearby FlossWare node.
 *
 * @property peerIdentity Identity of the discovered peer.
 * @property availableTransports Transports advertised by the peer.
 * @property signalStrength Optional signal quality indicator (0-100), or -1
 *   if unknown.
 * @property discoveryMethod How the peer was discovered.
 */
data class DiscoveryResult(
    val peerIdentity: PeerIdentity,
    val availableTransports: Set<TransportType>,
    val signalStrength: Int = SIGNAL_UNKNOWN,
    val discoveryMethod: DiscoveryMethod = DiscoveryMethod.NSD,
) {
    companion object {
        /** Sentinel value indicating signal strength is not available. */
        const val SIGNAL_UNKNOWN = -1
    }
}

/**
 * The mechanism used to discover a peer.
 */
enum class DiscoveryMethod {
    /** Android NSD (mDNS / DNS-SD). */
    NSD,

    /** Manual / pre-configured endpoint. */
    MANUAL,
}
