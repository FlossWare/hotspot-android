package org.flossware.hotspot.diagnostics

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagnosticsManagerTest {

    private lateinit var manager: DiagnosticsManager

    @Before
    fun setUp() {
        val prefs = FakeSharedPreferences()
        val context = object : android.content.ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        }
        manager = DiagnosticsManager(context)
    }

    @Test
    fun `recordEvent adds event to buffer`() {
        val event = DiagnosticEvent(
            transport = TransportType.WIFI_DIRECT,
            phase = ConnectionPhase.HANDSHAKE,
        )
        manager.recordEvent(event)
        assertEquals(1, manager.size)
        assertEquals(event, manager.getEvents().first())
    }

    @Test
    fun `ring buffer drops oldest when full`() {
        for (i in 1..DiagnosticsManager.MAX_EVENTS + 10) {
            manager.recordEvent(
                DiagnosticEvent(
                    timestamp = i.toLong(),
                    transport = TransportType.WIFI_DIRECT,
                    phase = ConnectionPhase.ESTABLISHED,
                ),
            )
        }
        assertEquals(DiagnosticsManager.MAX_EVENTS, manager.size)
        assertEquals(11L, manager.getEvents().first().timestamp)
    }

    @Test
    fun `getLatestEvent returns null when empty`() {
        assertNull(manager.getLatestEvent())
    }

    @Test
    fun `getLatestEvent returns last recorded event`() {
        manager.recordEvent(
            DiagnosticEvent(
                timestamp = 1L,
                transport = TransportType.WIFI_DIRECT,
                phase = ConnectionPhase.HANDSHAKE,
            ),
        )
        manager.recordEvent(
            DiagnosticEvent(
                timestamp = 2L,
                transport = TransportType.WIFI_DIRECT,
                phase = ConnectionPhase.ESTABLISHED,
            ),
        )
        assertEquals(2L, manager.getLatestEvent()?.timestamp)
    }

    @Test
    fun `clear empties the buffer`() {
        manager.recordEvent(
            DiagnosticEvent(
                transport = TransportType.WIFI_DIRECT,
                phase = ConnectionPhase.HANDSHAKE,
            ),
        )
        assertEquals(1, manager.size)
        manager.clear()
        assertEquals(0, manager.size)
    }

    @Test
    fun `onSessionStart records handshake event`() {
        manager.onSessionStart(TransportType.BLUETOOTH)
        val event = manager.getLatestEvent()
        assertNotNull(event)
        assertEquals(TransportType.BLUETOOTH, event!!.transport)
        assertEquals(ConnectionPhase.HANDSHAKE, event.phase)
    }

    @Test
    fun `onSessionEstablished records established event`() {
        val metrics = DiagnosticMetrics(activeConnections = 2)
        manager.onSessionEstablished(TransportType.WIFI_DIRECT, metrics)
        val event = manager.getLatestEvent()
        assertNotNull(event)
        assertEquals(ConnectionPhase.ESTABLISHED, event!!.phase)
        assertEquals(2, event.metrics.activeConnections)
    }

    @Test
    fun `onError records failed event with error message`() {
        val metrics = DiagnosticMetrics()
        manager.onError(TransportType.WIFI_DIRECT, "Connection lost", metrics)
        val event = manager.getLatestEvent()
        assertNotNull(event)
        assertEquals(ConnectionPhase.FAILED, event!!.phase)
        assertEquals(listOf("Connection lost"), event.errors)
    }

    @Test
    fun `onSessionEnd records closed event and persists session`() {
        manager.onSessionStart(TransportType.WIFI_DIRECT)
        val metrics = DiagnosticMetrics(bytesTx = 1024)
        manager.onSessionEnd(metrics)

        val event = manager.getLatestEvent()
        assertNotNull(event)
        assertEquals(ConnectionPhase.CLOSED, event!!.phase)

        val sessions = manager.getSessionHistory().getSessions()
        assertEquals(1, sessions.size)
        assertEquals(TransportType.WIFI_DIRECT, sessions[0].transport)
        assertEquals(1024L, sessions[0].bytesTransferred)
    }

    @Test
    fun `exportReport produces valid JSON`() {
        manager.onSessionStart(TransportType.WIFI_DIRECT)
        manager.onSessionEstablished(
            TransportType.WIFI_DIRECT,
            DiagnosticMetrics(activeConnections = 1),
        )
        val report = manager.exportReport()
        assertTrue(report.contains("\"events\""))
        assertTrue(report.contains("\"sessionHistory\""))
        assertTrue(report.contains("WIFI_DIRECT"))
        assertTrue(report.contains("ESTABLISHED"))
    }

    @Test
    fun `getEvents returns defensive copy`() {
        manager.recordEvent(
            DiagnosticEvent(
                transport = TransportType.WIFI_DIRECT,
                phase = ConnectionPhase.HANDSHAKE,
            ),
        )
        val events = manager.getEvents()
        manager.clear()
        assertEquals(1, events.size)
        assertEquals(0, manager.size)
    }

    @Test
    fun `session error summary captures last error`() {
        manager.onSessionStart(TransportType.WIFI_DIRECT)
        manager.onError(TransportType.WIFI_DIRECT, "Error 1", DiagnosticMetrics())
        manager.onError(TransportType.WIFI_DIRECT, "Error 2", DiagnosticMetrics())
        manager.onSessionEnd(DiagnosticMetrics(bytesTx = 100))

        val sessions = manager.getSessionHistory().getSessions()
        assertEquals(1, sessions.size)
        assertEquals("Error 2", sessions[0].errorSummary)
    }

    @Test
    fun `multiple sessions are recorded in order`() {
        manager.onSessionStart(TransportType.WIFI_DIRECT)
        manager.onSessionEnd(DiagnosticMetrics(bytesTx = 100))

        manager.onSessionStart(TransportType.BLUETOOTH)
        manager.onSessionEnd(DiagnosticMetrics(bytesTx = 200))

        val sessions = manager.getSessionHistory().getSessions()
        assertEquals(2, sessions.size)
        assertEquals(TransportType.BLUETOOTH, sessions[0].transport)
        assertEquals(TransportType.WIFI_DIRECT, sessions[1].transport)
    }
}

/**
 * Minimal fake SharedPreferences for unit testing without Android framework.
 */
internal class FakeSharedPreferences : SharedPreferences {
    private val data = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = data.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? =
        data[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST")
        (data[key] as? MutableSet<String>) ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        data[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor(data)
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private class FakeEditor(
        private val data: MutableMap<String, Any?>,
    ) : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) pending[key] = value; return this
        }
        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor {
            if (key != null) pending[key] = values; return this
        }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) pending[key] = value; return this
        }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) pending[key] = value; return this
        }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) pending[key] = value; return this
        }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) pending[key] = value; return this
        }
        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) removals.add(key); return this
        }
        override fun clear(): SharedPreferences.Editor {
            data.clear(); return this
        }
        override fun commit(): Boolean { applyChanges(); return true }
        override fun apply() { applyChanges() }
        private fun applyChanges() {
            removals.forEach { data.remove(it) }
            data.putAll(pending)
            pending.clear()
            removals.clear()
        }
    }
}
