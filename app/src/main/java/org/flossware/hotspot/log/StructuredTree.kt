package org.flossware.hotspot.log

import android.util.Log
import org.json.JSONObject
import timber.log.Timber
import java.security.MessageDigest
import java.time.Instant

/**
 * A [Timber.Tree] that outputs JSON-formatted structured log events.
 *
 * Each log line is a JSON object containing:
 * - `timestamp` -- ISO-8601 instant
 * - `level` -- one of V, D, I, W, E
 * - `tag` -- the log tag (typically the class name)
 * - `event` -- the log message with privacy protections applied
 * - `session_id` -- unique identifier for this app session
 * - `error` -- exception message (when a throwable is present)
 *
 * Privacy protections:
 * - IP addresses are replaced with a short SHA-256 hash (except loopback/unspecified)
 * - Full URLs are stripped to host-only
 */
class StructuredTree(private val sessionId: String) : Timber.Tree() {

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val level = priorityToLevel(priority)
        val sanitizedMessage = sanitize(message)

        // Record in the ring buffer so log export continues to work
        LogCollector.record(tag ?: "unknown", level, sanitizedMessage)

        val json = JSONObject().apply {
            put("timestamp", Instant.now().toString())
            put("level", level)
            put("tag", tag ?: "unknown")
            put("event", sanitizedMessage)
            put("session_id", sessionId)
            if (t != null) {
                put("error", t.message ?: t.javaClass.simpleName)
            }
        }

        Log.println(priority, tag ?: "StructuredLog", json.toString())
    }

    companion object {
        private val IP_PATTERN = Regex(
            """\b(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})\b""",
        )
        private val URL_PATH_PATTERN = Regex(
            """(https?://[^/\s]+)/[^\s]*""",
        )
        private val WELL_KNOWN_IPS = setOf(
            "127.0.0.1", "0.0.0.0", "192.168.49.1", "10.0.0.2",
            "8.8.8.8", "8.8.4.4", "198.18.0.2",
        )

        internal fun priorityToLevel(priority: Int): String = when (priority) {
            Log.VERBOSE -> "V"
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            Log.ASSERT -> "A"
            else -> "?"
        }

        internal fun sanitize(message: String): String {
            var result = message
            result = IP_PATTERN.replace(result) { match ->
                val ip = match.groupValues[1]
                if (ip in WELL_KNOWN_IPS) ip else hashIp(ip)
            }
            result = URL_PATH_PATTERN.replace(result) { match ->
                match.groupValues[1] + "/***"
            }
            return result
        }

        internal fun hashIp(ip: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(ip.toByteArray(Charsets.UTF_8))
            return "ip:" + hash.take(4).joinToString("") { "%02x".format(it) }
        }
    }
}
