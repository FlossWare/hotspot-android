package org.flossware.hotspot.diagnostics

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Summary of a single hotspot session, stored for historical diagnostics.
 */
data class SessionSummary(
    val startTime: Long,
    val durationSeconds: Long,
    val transport: TransportType,
    val errorSummary: String,
    val bytesTransferred: Long,
)

/**
 * Tracks and persists the last [MAX_SESSIONS] session summaries using SharedPreferences.
 *
 * Each session summary is stored as a JSON array. Older sessions beyond the limit
 * are dropped automatically.
 */
class SessionHistory(private val context: Context) {

    /**
     * Returns the list of persisted session summaries, most recent first.
     */
    fun getSessions(): List<SessionSummary> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_SESSIONS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                SessionSummary(
                    startTime = obj.getLong("startTime"),
                    durationSeconds = obj.getLong("durationSeconds"),
                    transport = TransportType.valueOf(obj.getString("transport")),
                    errorSummary = obj.optString("errorSummary", ""),
                    bytesTransferred = obj.getLong("bytesTransferred"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Records a new session summary, keeping only the most recent [MAX_SESSIONS].
     */
    fun recordSession(session: SessionSummary) {
        val sessions = getSessions().toMutableList()
        sessions.add(0, session)
        while (sessions.size > MAX_SESSIONS) {
            sessions.removeAt(sessions.size - 1)
        }
        persist(sessions)
    }

    /**
     * Exports all session summaries as a JSON array.
     */
    fun toJsonArray(): JSONArray {
        val array = JSONArray()
        for (session in getSessions()) {
            array.put(JSONObject().apply {
                put("startTime", session.startTime)
                put("durationSeconds", session.durationSeconds)
                put("transport", session.transport.name)
                put("errorSummary", session.errorSummary)
                put("bytesTransferred", session.bytesTransferred)
            })
        }
        return array
    }

    /**
     * Clears all persisted session history.
     */
    fun clear() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SESSIONS)
            .apply()
    }

    private fun persist(sessions: List<SessionSummary>) {
        val array = JSONArray()
        for (session in sessions) {
            array.put(JSONObject().apply {
                put("startTime", session.startTime)
                put("durationSeconds", session.durationSeconds)
                put("transport", session.transport.name)
                put("errorSummary", session.errorSummary)
                put("bytesTransferred", session.bytesTransferred)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SESSIONS, array.toString())
            .apply()
    }

    companion object {
        internal const val MAX_SESSIONS = 10
        internal const val PREFS_NAME = "diagnostics_session_history"
        internal const val KEY_SESSIONS = "sessions"
    }
}
