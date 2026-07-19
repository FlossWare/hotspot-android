package org.flossware.hotspot.transport

/**
 * Thrown when a transport operation exceeds its configured timeout.
 */
class TransportTimeoutException(
    message: String = "Transport operation timed out",
    cause: Throwable? = null,
) : Exception(message, cause)
