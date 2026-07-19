package org.flossware.hotspot.transport

/**
 * Selects the best available transport based on a [QosHint].
 *
 * Each hint maps to a priority ordering:
 * - [QosHint.LATENCY_SENSITIVE]:    Wi-Fi Direct > USB > Bluetooth
 * - [QosHint.THROUGHPUT_OPTIMIZED]: Wi-Fi Direct > USB > Bluetooth
 * - [QosHint.POWER_EFFICIENT]:      Bluetooth > USB > Wi-Fi Direct
 *
 * The selector returns the highest-priority transport that is present in
 * [availableTransports], or `null` if none are available.
 */
class TransportSelector {

    /**
     * Returns the best [TransportType] for the given [hint] from the set of
     * [availableTransports], or `null` if [availableTransports] is empty.
     */
    fun select(hint: QosHint, availableTransports: Set<TransportType>): TransportType? {
        if (availableTransports.isEmpty()) return null
        val priority = priorityFor(hint)
        return priority.firstOrNull { it in availableTransports }
    }

    companion object {
        private val LATENCY_PRIORITY = listOf(
            TransportType.WIFI_DIRECT,
            TransportType.USB,
            TransportType.BLUETOOTH,
        )

        private val THROUGHPUT_PRIORITY = listOf(
            TransportType.WIFI_DIRECT,
            TransportType.USB,
            TransportType.BLUETOOTH,
        )

        private val POWER_PRIORITY = listOf(
            TransportType.BLUETOOTH,
            TransportType.USB,
            TransportType.WIFI_DIRECT,
        )

        /**
         * Returns the priority ordering for the given [hint].
         */
        internal fun priorityFor(hint: QosHint): List<TransportType> = when (hint) {
            QosHint.LATENCY_SENSITIVE -> LATENCY_PRIORITY
            QosHint.THROUGHPUT_OPTIMIZED -> THROUGHPUT_PRIORITY
            QosHint.POWER_EFFICIENT -> POWER_PRIORITY
        }
    }
}
