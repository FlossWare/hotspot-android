package org.flossware.hotspot.model

/**
 * Tracks the health status of the tunnel watchdog.
 *
 * The watchdog periodically checks proxy, Wi-Fi Direct, Bluetooth, and USB
 * subsystems and initiates escalating recovery when failures are detected.
 */
data class WatchdogState(
    /** Current health status of the tunnel. */
    val healthStatus: HealthStatus = HealthStatus.HEALTHY,
    /** Number of soft recoveries (proxy reconnect) performed. */
    val softRecoveryCount: Int = 0,
    /** Number of medium recoveries (transport restart) performed. */
    val mediumRecoveryCount: Int = 0,
    /** Number of hard recoveries (full service recreate) performed in the current hour window. */
    val hardRecoveryCount: Int = 0,
    /** Whether the system has entered degraded mode (too many hard restarts). */
    val isDegraded: Boolean = false,
    /** Elapsed-realtime timestamp (ms) of the last health check. */
    val lastCheckTimestamp: Long = 0L,
    /** Elapsed-realtime timestamp (ms) when the system last became healthy. */
    val lastHealthyTimestamp: Long = 0L,
    /** Elapsed-realtime timestamp (ms) of the first hard recovery in the current hour window. */
    val hardRecoveryWindowStart: Long = 0L,
    /** Current backoff delay in milliseconds for recovery attempts. */
    val currentBackoffMs: Long = INITIAL_BACKOFF_MS,
    /** Number of consecutive failed health checks. */
    val consecutiveFailures: Int = 0,
) {
    companion object {
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 32_000L
        const val MAX_HARD_RESTARTS_PER_HOUR = 5
        const val HEALTHY_RESET_MS = 30L * 60 * 1_000 // 30 minutes
        const val HARD_RECOVERY_WINDOW_MS = 60L * 60 * 1_000 // 1 hour
        const val HEALTH_CHECK_INTERVAL_MS = 10_000L
    }
}

/** Health status levels for the watchdog. */
enum class HealthStatus {
    /** All subsystems are functioning normally. */
    HEALTHY,

    /** A failure was detected; recovery is in progress. */
    RECOVERING,

    /** The system entered degraded mode after too many hard restarts. */
    DEGRADED,
}
