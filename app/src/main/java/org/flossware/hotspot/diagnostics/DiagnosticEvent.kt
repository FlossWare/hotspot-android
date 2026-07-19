package org.flossware.hotspot.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * The phase of a connection lifecycle.
 */
enum class ConnectionPhase {
    HANDSHAKE,
    ESTABLISHED,
    DEGRADED,
    FAILED,
    CLOSED,
}

/**
 * The type of network transport being used.
 */
enum class TransportType {
    WIFI_DIRECT,
    BLUETOOTH,
    USB,
}

/**
 * Metrics snapshot for a diagnostic event.
 */
data class DiagnosticMetrics(
    val activeConnections: Int = 0,
    val bytesTx: Long = 0L,
    val bytesRx: Long = 0L,
    val uptimeSeconds: Long = 0L,
)

/**
 * A single diagnostic event capturing the state of the connection at a point in time.
 *
 * Privacy: IP addresses are hashed with SHA-256, truncated to 8 hex characters.
 * No full URLs or personally identifiable information is stored.
 */
data class DiagnosticEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val transport: TransportType,
    val phase: ConnectionPhase,
    val metrics: DiagnosticMetrics = DiagnosticMetrics(),
    val errors: List<String> = emptyList(),
) {

    /**
     * Converts this event to an anonymized JSON object for export.
     */
    fun toJson(): JSONObject = JSONObject().apply {
        put("timestamp", timestamp)
        put("transport", transport.name)
        put("phase", phase.name)
        put("metrics", JSONObject().apply {
            put("activeConnections", metrics.activeConnections)
            put("bytesTx", metrics.bytesTx)
            put("bytesRx", metrics.bytesRx)
            put("uptimeSeconds", metrics.uptimeSeconds)
        })
        put("errors", JSONArray(errors))
    }

    companion object {
        private const val HASH_LENGTH = 8

        /**
         * Hashes an IP address using SHA-256 and truncates to [HASH_LENGTH] hex characters.
         * Returns an empty string for null or blank input.
         */
        fun hashIp(ip: String?): String {
            if (ip.isNullOrBlank()) return ""
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(ip.toByteArray())
            return hash.joinToString("") { "%02x".format(it) }.take(HASH_LENGTH)
        }
    }
}
