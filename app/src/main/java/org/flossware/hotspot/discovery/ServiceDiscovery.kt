package org.flossware.hotspot.discovery

import kotlinx.coroutines.flow.Flow

/**
 * Discovers nearby FlossWare nodes on the local network.
 */
interface ServiceDiscovery {

    /**
     * Starts discovering peers and emits results as they are found.
     * The returned [Flow] is hot: it keeps discovering until collected
     * flow is cancelled.
     */
    fun discover(): Flow<DiscoveryResult>

    /**
     * Registers this device as a discoverable FlossWare service.
     *
     * @param fingerprint Public key fingerprint to advertise.
     * @param transports Set of available transports to advertise.
     * @param port The port the service is listening on.
     */
    fun register(fingerprint: String, transports: Set<org.flossware.hotspot.transport.TransportType>, port: Int)

    /**
     * Unregisters the service previously registered via [register].
     */
    fun unregister()
}
