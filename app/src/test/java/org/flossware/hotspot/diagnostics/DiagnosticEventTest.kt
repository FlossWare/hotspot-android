package org.flossware.hotspot.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticEventTest {

    @Test
    fun `default metrics have zero values`() {
        val metrics = DiagnosticMetrics()
        assertEquals(0, metrics.activeConnections)
        assertEquals(0L, metrics.bytesTx)
        assertEquals(0L, metrics.bytesRx)
        assertEquals(0L, metrics.uptimeSeconds)
    }

    @Test
    fun `event has correct default values`() {
        val event = DiagnosticEvent(
            transport = TransportType.WIFI_DIRECT,
            phase = ConnectionPhase.HANDSHAKE,
        )
        assertTrue(event.timestamp > 0)
        assertEquals(TransportType.WIFI_DIRECT, event.transport)
        assertEquals(ConnectionPhase.HANDSHAKE, event.phase)
        assertTrue(event.errors.isEmpty())
    }

    @Test
    fun `event with errors stores error list`() {
        val errors = listOf("Connection timeout", "DNS failure")
        val event = DiagnosticEvent(
            transport = TransportType.BLUETOOTH,
            phase = ConnectionPhase.FAILED,
            errors = errors,
        )
        assertEquals(2, event.errors.size)
        assertEquals("Connection timeout", event.errors[0])
        assertEquals("DNS failure", event.errors[1])
    }

    @Test
    fun `toJson includes all fields`() {
        val metrics = DiagnosticMetrics(
            activeConnections = 3,
            bytesTx = 1024,
            bytesRx = 2048,
            uptimeSeconds = 60,
        )
        val event = DiagnosticEvent(
            timestamp = 1000L,
            transport = TransportType.USB,
            phase = ConnectionPhase.ESTABLISHED,
            metrics = metrics,
            errors = listOf("warning"),
        )
        val json = event.toJson()
        assertEquals(1000L, json.getLong("timestamp"))
        assertEquals("USB", json.getString("transport"))
        assertEquals("ESTABLISHED", json.getString("phase"))

        val metricsJson = json.getJSONObject("metrics")
        assertEquals(3, metricsJson.getInt("activeConnections"))
        assertEquals(1024L, metricsJson.getLong("bytesTx"))
        assertEquals(2048L, metricsJson.getLong("bytesRx"))
        assertEquals(60L, metricsJson.getLong("uptimeSeconds"))

        val errorsJson = json.getJSONArray("errors")
        assertEquals(1, errorsJson.length())
        assertEquals("warning", errorsJson.getString(0))
    }

    @Test
    fun `toJson with empty errors produces empty array`() {
        val event = DiagnosticEvent(
            transport = TransportType.WIFI_DIRECT,
            phase = ConnectionPhase.CLOSED,
        )
        val json = event.toJson()
        assertEquals(0, json.getJSONArray("errors").length())
    }

    @Test
    fun `hashIp produces 8 character hex string`() {
        val hash = DiagnosticEvent.hashIp("192.168.1.1")
        assertEquals(8, hash.length)
        assertTrue(hash.matches(Regex("[0-9a-f]+")))
    }

    @Test
    fun `hashIp is deterministic`() {
        val hash1 = DiagnosticEvent.hashIp("10.0.0.1")
        val hash2 = DiagnosticEvent.hashIp("10.0.0.1")
        assertEquals(hash1, hash2)
    }

    @Test
    fun `hashIp produces different hashes for different IPs`() {
        val hash1 = DiagnosticEvent.hashIp("192.168.1.1")
        val hash2 = DiagnosticEvent.hashIp("192.168.1.2")
        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `hashIp returns empty string for null`() {
        assertEquals("", DiagnosticEvent.hashIp(null))
    }

    @Test
    fun `hashIp returns empty string for blank`() {
        assertEquals("", DiagnosticEvent.hashIp(""))
        assertEquals("", DiagnosticEvent.hashIp("   "))
    }

    @Test
    fun `ConnectionPhase enum has all expected values`() {
        val phases = ConnectionPhase.values()
        assertEquals(5, phases.size)
        assertTrue(phases.contains(ConnectionPhase.HANDSHAKE))
        assertTrue(phases.contains(ConnectionPhase.ESTABLISHED))
        assertTrue(phases.contains(ConnectionPhase.DEGRADED))
        assertTrue(phases.contains(ConnectionPhase.FAILED))
        assertTrue(phases.contains(ConnectionPhase.CLOSED))
    }

    @Test
    fun `TransportType enum has all expected values`() {
        val types = TransportType.values()
        assertEquals(3, types.size)
        assertTrue(types.contains(TransportType.WIFI_DIRECT))
        assertTrue(types.contains(TransportType.BLUETOOTH))
        assertTrue(types.contains(TransportType.USB))
    }

    @Test
    fun `metrics equality works`() {
        val a = DiagnosticMetrics(activeConnections = 2, bytesTx = 100)
        val b = DiagnosticMetrics(activeConnections = 2, bytesTx = 100)
        assertEquals(a, b)
    }

    @Test
    fun `event equality works`() {
        val a = DiagnosticEvent(
            timestamp = 1000L,
            transport = TransportType.WIFI_DIRECT,
            phase = ConnectionPhase.ESTABLISHED,
        )
        val b = DiagnosticEvent(
            timestamp = 1000L,
            transport = TransportType.WIFI_DIRECT,
            phase = ConnectionPhase.ESTABLISHED,
        )
        assertEquals(a, b)
    }

    @Test
    fun `event copy preserves fields`() {
        val event = DiagnosticEvent(
            timestamp = 1000L,
            transport = TransportType.BLUETOOTH,
            phase = ConnectionPhase.HANDSHAKE,
            errors = listOf("err"),
        )
        val copied = event.copy(phase = ConnectionPhase.ESTABLISHED)
        assertEquals(TransportType.BLUETOOTH, copied.transport)
        assertEquals(ConnectionPhase.ESTABLISHED, copied.phase)
        assertEquals(1000L, copied.timestamp)
        assertEquals(listOf("err"), copied.errors)
    }
}
