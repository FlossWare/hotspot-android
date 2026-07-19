package org.flossware.hotspot.transport

import kotlinx.coroutines.flow.Flow

/**
 * A bidirectional communication session with a remote peer over a specific
 * transport.
 *
 * Implementations must be safe to call [send] from multiple coroutines
 * concurrently. The [receive] flow emits data frames as they arrive and
 * completes when the peer closes the connection.
 */
interface TransportSession {
    /** Identity of the remote peer. */
    val peer: PeerIdentity

    /** Transport type backing this session. */
    val transport: TransportType

    /**
     * Sends [data] to the remote peer.
     *
     * @throws TransportDisconnectedException if the session is no longer connected.
     * @throws TransportTimeoutException if the send does not complete in time.
     */
    suspend fun send(data: ByteArray)

    /**
     * Returns a cold [Flow] of byte arrays received from the remote peer.
     * The flow completes normally when the peer closes the connection.
     */
    fun receive(): Flow<ByteArray>

    /** Closes the session and releases underlying resources. */
    suspend fun close()

    /** True while the underlying connection is alive. */
    val isConnected: Boolean
}
