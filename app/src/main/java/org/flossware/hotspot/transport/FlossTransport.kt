package org.flossware.hotspot.transport

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over a peer-to-peer transport mechanism (Wi-Fi Direct,
 * Bluetooth, USB).
 *
 * Each implementation wraps the corresponding platform manager without
 * replacing it, allowing the existing [HotspotService] integration to
 * continue working.
 */
interface FlossTransport {
    /**
     * Opens a session to the given [peer] over this transport.
     *
     * @throws TransportDisconnectedException if the transport is unavailable.
     * @throws TransportTimeoutException if the connection cannot be established
     *   within the implementation's timeout.
     */
    suspend fun connect(peer: PeerIdentity): TransportSession

    /**
     * Returns a [Flow] that emits incoming [TransportSession]s from remote
     * peers. The flow is active as long as this transport is listening.
     */
    suspend fun listen(): Flow<TransportSession>

    /** Returns the list of transport types provided by this implementation. */
    fun availableTransports(): List<TransportType>

    /**
     * Returns the preferred [TransportType] from those available, given a
     * quality-of-service [hint].
     */
    fun preferredTransport(hint: QosHint): TransportType
}
