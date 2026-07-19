package org.flossware.hotspot.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransportSelectorTest {

    private val selector = TransportSelector()

    // --- Empty availability ---

    @Test
    fun `select returns null when no transports are available`() {
        assertNull(selector.select(QosHint.LATENCY_SENSITIVE, emptySet()))
        assertNull(selector.select(QosHint.THROUGHPUT_OPTIMIZED, emptySet()))
        assertNull(selector.select(QosHint.POWER_EFFICIENT, emptySet()))
    }

    // --- LATENCY_SENSITIVE ---

    @Test
    fun `LATENCY_SENSITIVE prefers Wi-Fi Direct when all available`() {
        val result = selector.select(QosHint.LATENCY_SENSITIVE, TransportType.entries.toSet())
        assertEquals(TransportType.WIFI_DIRECT, result)
    }

    @Test
    fun `LATENCY_SENSITIVE falls back to USB when Wi-Fi Direct unavailable`() {
        val available = setOf(TransportType.USB, TransportType.BLUETOOTH)
        assertEquals(TransportType.USB, selector.select(QosHint.LATENCY_SENSITIVE, available))
    }

    @Test
    fun `LATENCY_SENSITIVE falls back to Bluetooth when only option`() {
        val available = setOf(TransportType.BLUETOOTH)
        assertEquals(TransportType.BLUETOOTH, selector.select(QosHint.LATENCY_SENSITIVE, available))
    }

    // --- THROUGHPUT_OPTIMIZED ---

    @Test
    fun `THROUGHPUT_OPTIMIZED prefers Wi-Fi Direct when all available`() {
        val result = selector.select(QosHint.THROUGHPUT_OPTIMIZED, TransportType.entries.toSet())
        assertEquals(TransportType.WIFI_DIRECT, result)
    }

    @Test
    fun `THROUGHPUT_OPTIMIZED falls back to USB when Wi-Fi Direct unavailable`() {
        val available = setOf(TransportType.USB, TransportType.BLUETOOTH)
        assertEquals(TransportType.USB, selector.select(QosHint.THROUGHPUT_OPTIMIZED, available))
    }

    @Test
    fun `THROUGHPUT_OPTIMIZED falls back to Bluetooth when only option`() {
        val available = setOf(TransportType.BLUETOOTH)
        assertEquals(TransportType.BLUETOOTH, selector.select(QosHint.THROUGHPUT_OPTIMIZED, available))
    }

    // --- POWER_EFFICIENT ---

    @Test
    fun `POWER_EFFICIENT prefers Bluetooth when all available`() {
        val result = selector.select(QosHint.POWER_EFFICIENT, TransportType.entries.toSet())
        assertEquals(TransportType.BLUETOOTH, result)
    }

    @Test
    fun `POWER_EFFICIENT falls back to USB when Bluetooth unavailable`() {
        val available = setOf(TransportType.USB, TransportType.WIFI_DIRECT)
        assertEquals(TransportType.USB, selector.select(QosHint.POWER_EFFICIENT, available))
    }

    @Test
    fun `POWER_EFFICIENT falls back to Wi-Fi Direct when only option`() {
        val available = setOf(TransportType.WIFI_DIRECT)
        assertEquals(TransportType.WIFI_DIRECT, selector.select(QosHint.POWER_EFFICIENT, available))
    }

    // --- Single transport available ---

    @Test
    fun `select returns the only available transport regardless of hint`() {
        for (hint in QosHint.entries) {
            for (transport in TransportType.entries) {
                val result = selector.select(hint, setOf(transport))
                assertEquals(
                    "Expected $transport for hint $hint with single option",
                    transport,
                    result,
                )
            }
        }
    }

    // --- Priority ordering verification ---

    @Test
    fun `priorityFor LATENCY_SENSITIVE returns Wi-Fi, USB, Bluetooth order`() {
        val priority = TransportSelector.priorityFor(QosHint.LATENCY_SENSITIVE)
        assertEquals(
            listOf(TransportType.WIFI_DIRECT, TransportType.USB, TransportType.BLUETOOTH),
            priority,
        )
    }

    @Test
    fun `priorityFor THROUGHPUT_OPTIMIZED returns Wi-Fi, USB, Bluetooth order`() {
        val priority = TransportSelector.priorityFor(QosHint.THROUGHPUT_OPTIMIZED)
        assertEquals(
            listOf(TransportType.WIFI_DIRECT, TransportType.USB, TransportType.BLUETOOTH),
            priority,
        )
    }

    @Test
    fun `priorityFor POWER_EFFICIENT returns Bluetooth, USB, Wi-Fi order`() {
        val priority = TransportSelector.priorityFor(QosHint.POWER_EFFICIENT)
        assertEquals(
            listOf(TransportType.BLUETOOTH, TransportType.USB, TransportType.WIFI_DIRECT),
            priority,
        )
    }

    // --- All hints produce a result when all transports are available ---

    @Test
    fun `all hints return a non-null result when all transports available`() {
        val all = TransportType.entries.toSet()
        for (hint in QosHint.entries) {
            val result = selector.select(hint, all)
            assertEquals(
                "Expected non-null for $hint",
                true,
                result != null,
            )
        }
    }
}
