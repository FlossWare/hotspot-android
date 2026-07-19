package org.flossware.hotspot.diagnostics

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Central diagnostics manager that records connection events in a ring buffer
 * and persists session phase changes to SharedPreferences.
 *
 * Thread-safe: all buffer mutations are synchronized.
 */
class DiagnosticsManager(private val context: Context) {

    private val events = ArrayDeque<DiagnosticEvent>(MAX_EVENTS)
    private val lock = Any()
    private val sessionHistory = SessionHistory(context)

    private var sessionStartTime: Long = 0L
    private var sessionTransport: TransportType = TransportType.WIFI_DIRECT
    private var sessionErrors = mutableListOf<String>()

    /**
     * Records a diagnostic event in the ring buffer.
     * Phase changes are also persisted to SharedPreferences.
     */
    fun recordEvent(event: DiagnosticEvent) {
        synchronized(lock) {
            if (events.size >= MAX_EVENTS) {
                events.removeFirst()
            }
            events.addLast(event)
        }
        persistPhaseChange(event)
    }

    /**
     * Records the start of a new session.
     */
    fun onSessionStart(transport: TransportType) {
        sessionStartTime = System.currentTimeMillis()
        sessionTransport = transport
        sessionErrors.clear()
        recordEvent(
            DiagnosticEvent(
                transport = transport,
                phase = ConnectionPhase.HANDSHAKE,
            ),
        )
    }

    /**
     * Records that the session has been established with initial metrics.
     */
    fun onSessionEstablished(transport: TransportType, metrics: DiagnosticMetrics) {
        recordEvent(
            DiagnosticEvent(
                transport = transport,
                phase = ConnectionPhase.ESTABLISHED,
                metrics = metrics,
            ),
        )
    }

    /**
     * Records an error during the session.
     */
    fun onError(transport: TransportType, error: String, metrics: DiagnosticMetrics) {
        sessionErrors.add(error)
        recordEvent(
            DiagnosticEvent(
                transport = transport,
                phase = ConnectionPhase.FAILED,
                metrics = metrics,
                errors = listOf(error),
            ),
        )
    }

    /**
     * Records the end of a session and persists the session summary.
     */
    fun onSessionEnd(metrics: DiagnosticMetrics) {
        val duration = if (sessionStartTime > 0L) {
            (System.currentTimeMillis() - sessionStartTime) / MILLIS_PER_SECOND
        } else {
            0L
        }

        recordEvent(
            DiagnosticEvent(
                transport = sessionTransport,
                phase = ConnectionPhase.CLOSED,
                metrics = metrics,
            ),
        )

        sessionHistory.recordSession(
            SessionSummary(
                startTime = sessionStartTime,
                durationSeconds = duration,
                transport = sessionTransport,
                errorSummary = sessionErrors.lastOrNull() ?: "",
                bytesTransferred = metrics.bytesTx,
            ),
        )

        sessionStartTime = 0L
        sessionErrors.clear()
    }

    /**
     * Returns a snapshot of all events currently in the buffer.
     */
    fun getEvents(): List<DiagnosticEvent> {
        synchronized(lock) {
            return events.toList()
        }
    }

    /**
     * Returns the most recent event, or null if the buffer is empty.
     */
    fun getLatestEvent(): DiagnosticEvent? {
        synchronized(lock) {
            return events.lastOrNull()
        }
    }

    /**
     * Returns the session history tracker.
     */
    fun getSessionHistory(): SessionHistory = sessionHistory

    /**
     * Exports an anonymized diagnostic report as a JSON string.
     * Includes all buffered events and session history.
     */
    fun exportReport(): String {
        val report = JSONObject()
        report.put("exportedAt", System.currentTimeMillis())

        val eventsArray = JSONArray()
        for (event in getEvents()) {
            eventsArray.put(event.toJson())
        }
        report.put("events", eventsArray)
        report.put("sessionHistory", sessionHistory.toJsonArray())

        return report.toString(2)
    }

    /**
     * Clears all buffered events.
     */
    fun clear() {
        synchronized(lock) {
            events.clear()
        }
    }

    internal val size: Int get() = synchronized(lock) { events.size }

    private fun persistPhaseChange(event: DiagnosticEvent) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_PHASE_CHANGES, null)
        val array = if (existing != null) JSONArray(existing) else JSONArray()

        array.put(event.toJson())

        // Keep only the most recent phase changes
        while (array.length() > MAX_PHASE_CHANGES) {
            array.remove(0)
        }

        prefs.edit()
            .putString(KEY_PHASE_CHANGES, array.toString())
            .apply()
    }

    companion object {
        internal const val MAX_EVENTS = 100
        internal const val MAX_PHASE_CHANGES = 10
        internal const val PREFS_NAME = "diagnostics_manager"
        internal const val KEY_PHASE_CHANGES = "phase_changes"
        private const val MILLIS_PER_SECOND = 1000L
    }
}
