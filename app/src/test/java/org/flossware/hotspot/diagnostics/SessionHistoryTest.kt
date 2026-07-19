package org.flossware.hotspot.diagnostics

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionHistoryTest {

    private lateinit var history: SessionHistory

    @Before
    fun setUp() {
        val prefs = FakeSharedPreferences()
        val context = object : android.content.ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        }
        history = SessionHistory(context)
    }

    @Test
    fun `initially empty`() {
        assertTrue(history.getSessions().isEmpty())
    }

    @Test
    fun `recordSession adds session`() {
        val session = SessionSummary(
            startTime = 1000L,
            durationSeconds = 60,
            transport = TransportType.WIFI_DIRECT,
            errorSummary = "",
            bytesTransferred = 1024,
        )
        history.recordSession(session)
        val sessions = history.getSessions()
        assertEquals(1, sessions.size)
        assertEquals(1000L, sessions[0].startTime)
        assertEquals(60L, sessions[0].durationSeconds)
        assertEquals(TransportType.WIFI_DIRECT, sessions[0].transport)
        assertEquals(1024L, sessions[0].bytesTransferred)
    }

    @Test
    fun `most recent session appears first`() {
        history.recordSession(
            SessionSummary(1000L, 60, TransportType.WIFI_DIRECT, "", 100),
        )
        history.recordSession(
            SessionSummary(2000L, 120, TransportType.BLUETOOTH, "", 200),
        )
        val sessions = history.getSessions()
        assertEquals(2, sessions.size)
        assertEquals(2000L, sessions[0].startTime)
        assertEquals(1000L, sessions[1].startTime)
    }

    @Test
    fun `limits to MAX_SESSIONS`() {
        for (i in 1..SessionHistory.MAX_SESSIONS + 3) {
            history.recordSession(
                SessionSummary(i.toLong(), 10, TransportType.WIFI_DIRECT, "", 0),
            )
        }
        val sessions = history.getSessions()
        assertEquals(SessionHistory.MAX_SESSIONS, sessions.size)
    }

    @Test
    fun `clear removes all sessions`() {
        history.recordSession(
            SessionSummary(1000L, 60, TransportType.WIFI_DIRECT, "", 100),
        )
        history.clear()
        assertTrue(history.getSessions().isEmpty())
    }

    @Test
    fun `toJsonArray includes all sessions`() {
        history.recordSession(
            SessionSummary(1000L, 60, TransportType.WIFI_DIRECT, "", 100),
        )
        history.recordSession(
            SessionSummary(2000L, 120, TransportType.BLUETOOTH, "error", 200),
        )
        val json = history.toJsonArray()
        assertEquals(2, json.length())

        val first = json.getJSONObject(0)
        assertEquals(2000L, first.getLong("startTime"))
        assertEquals("BLUETOOTH", first.getString("transport"))
        assertEquals("error", first.getString("errorSummary"))
    }

    @Test
    fun `session with error summary persists correctly`() {
        history.recordSession(
            SessionSummary(1000L, 60, TransportType.USB, "Network lost", 512),
        )
        val sessions = history.getSessions()
        assertEquals("Network lost", sessions[0].errorSummary)
        assertEquals(TransportType.USB, sessions[0].transport)
    }

    @Test
    fun `SessionSummary data class equality`() {
        val a = SessionSummary(1000L, 60, TransportType.WIFI_DIRECT, "", 100)
        val b = SessionSummary(1000L, 60, TransportType.WIFI_DIRECT, "", 100)
        assertEquals(a, b)
    }
}
