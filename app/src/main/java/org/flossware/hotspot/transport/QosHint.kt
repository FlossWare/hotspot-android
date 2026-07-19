package org.flossware.hotspot.transport

/**
 * Quality-of-service hints used by [TransportSelector] to choose the most
 * appropriate transport for a given use case.
 */
enum class QosHint {
    /** Minimize round-trip latency (Wi-Fi Direct preferred). */
    LATENCY_SENSITIVE,

    /** Maximize sustained throughput (Wi-Fi Direct preferred). */
    THROUGHPUT_OPTIMIZED,

    /** Minimize power consumption (Bluetooth preferred). */
    POWER_EFFICIENT,
}
