package org.flossware.hotspot.transport

/**
 * Thrown when an operation is attempted on a transport session that has been
 * disconnected or whose underlying connection was lost.
 */
class TransportDisconnectedException(
    message: String = "Transport session is disconnected",
    cause: Throwable? = null,
) : Exception(message, cause)
