package org.flossware.hotspot.service

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.flossware.hotspot.model.HealthStatus
import org.flossware.hotspot.model.WatchdogState

/**
 * Monitors tunnel health and initiates escalating recovery when Android
 * terminates or suspends services.
 *
 * Recovery levels:
 * - **Soft:** Reconnect the proxy (fast, least disruptive)
 * - **Medium:** Restart the active transport (Wi-Fi Direct / Bluetooth / USB)
 * - **Hard:** Full service recreate (most disruptive, rate-limited)
 *
 * After [WatchdogState.MAX_HARD_RESTARTS_PER_HOUR] hard restarts in one hour
 * the system enters degraded mode and stops attempting further recovery.
 * Counters reset after [WatchdogState.HEALTHY_RESET_MS] of uninterrupted health.
 */
class WatchdogManager(
    private val clock: Clock = SystemClockImpl,
    private val healthCheckIntervalMs: Long = WatchdogState.HEALTH_CHECK_INTERVAL_MS,
) {
    private val _state = MutableStateFlow(WatchdogState())
    val state: StateFlow<WatchdogState> = _state.asStateFlow()

    private var watchdogJob: Job? = null

    /** Callbacks invoked by the watchdog to query and act on subsystem state. */
    var proxyRunningCheck: () -> Boolean = { true }
    var wifiDirectHealthy: () -> Boolean = { true }
    var bluetoothHealthy: () -> Boolean = { true }
    var usbHealthy: () -> Boolean = { true }

    var onSoftRecovery: () -> Unit = {}
    var onMediumRecovery: () -> Unit = {}
    var onHardRecovery: () -> Unit = {}
    var onDegradedMode: () -> Unit = {}

    /**
     * Starts the health-check coroutine on the given [scope].
     * Only one watchdog loop runs at a time; calling start while already
     * running cancels the previous loop.
     */
    fun start(scope: CoroutineScope) {
        stop()
        val now = clock.elapsedRealtime()
        _state.value = WatchdogState(
            healthStatus = HealthStatus.HEALTHY,
            lastCheckTimestamp = now,
            lastHealthyTimestamp = now,
        )
        watchdogJob = scope.launch {
            runHealthCheckLoop()
        }
        Log.i(TAG, "Watchdog started")
    }

    /** Cancels the health-check coroutine and resets state. */
    fun stop() {
        watchdogJob?.cancel()
        watchdogJob = null
        _state.value = WatchdogState()
        Log.i(TAG, "Watchdog stopped")
    }

    /** Returns true when the watchdog coroutine is active. */
    val isRunning: Boolean get() = watchdogJob?.isActive == true

    // -- internal loop -----------------------------------------------------

    private suspend fun runHealthCheckLoop() {
        while (true) {
            delay(healthCheckIntervalMs)
            performHealthCheck()
        }
    }

    /**
     * Runs a single health check and triggers recovery if any subsystem
     * reports unhealthy.  Exposed as `internal` so unit tests can drive
     * the watchdog deterministically without coroutine timing.
     */
    internal fun performHealthCheck() {
        val now = clock.elapsedRealtime()
        val current = _state.value

        // If degraded, no further recovery attempts
        if (current.isDegraded) {
            _state.value = current.copy(lastCheckTimestamp = now)
            return
        }

        // Reset hard-recovery window if the hour has elapsed
        val windowState = maybeResetHardRecoveryWindow(current, now)

        // Reset all counters if healthy for HEALTHY_RESET_MS
        val resetState = maybeResetCounters(windowState, now)

        val healthy = checkAllSubsystems()

        if (healthy) {
            _state.value = resetState.copy(
                healthStatus = HealthStatus.HEALTHY,
                lastCheckTimestamp = now,
                lastHealthyTimestamp = now,
                consecutiveFailures = 0,
                currentBackoffMs = WatchdogState.INITIAL_BACKOFF_MS,
            )
            return
        }

        // Something is unhealthy -- escalate
        val failures = resetState.consecutiveFailures + 1
        val level = recoveryLevel(failures)
        Log.w(TAG, "Health check failed (consecutive=$failures, level=$level)")

        val recovered = when (level) {
            RecoveryLevel.SOFT -> {
                onSoftRecovery()
                resetState.copy(
                    softRecoveryCount = resetState.softRecoveryCount + 1,
                )
            }
            RecoveryLevel.MEDIUM -> {
                onMediumRecovery()
                resetState.copy(
                    mediumRecoveryCount = resetState.mediumRecoveryCount + 1,
                )
            }
            RecoveryLevel.HARD -> {
                val windowStart = if (resetState.hardRecoveryWindowStart == 0L) now
                    else resetState.hardRecoveryWindowStart
                val newHardCount = resetState.hardRecoveryCount + 1

                if (newHardCount >= WatchdogState.MAX_HARD_RESTARTS_PER_HOUR) {
                    Log.e(TAG, "Max hard restarts reached -- entering degraded mode")
                    onDegradedMode()
                    resetState.copy(
                        healthStatus = HealthStatus.DEGRADED,
                        isDegraded = true,
                        hardRecoveryCount = newHardCount,
                        hardRecoveryWindowStart = windowStart,
                    )
                } else {
                    onHardRecovery()
                    resetState.copy(
                        hardRecoveryCount = newHardCount,
                        hardRecoveryWindowStart = windowStart,
                    )
                }
            }
        }

        val backoff = nextBackoff(resetState.currentBackoffMs)
        _state.value = recovered.copy(
            healthStatus = if (recovered.isDegraded) HealthStatus.DEGRADED else HealthStatus.RECOVERING,
            lastCheckTimestamp = now,
            consecutiveFailures = failures,
            currentBackoffMs = backoff,
        )
    }

    // -- helpers -----------------------------------------------------------

    private fun checkAllSubsystems(): Boolean {
        return try {
            proxyRunningCheck() && wifiDirectHealthy() && bluetoothHealthy() && usbHealthy()
        } catch (e: Exception) {
            Log.w(TAG, "Subsystem check threw: ${e.message}")
            false
        }
    }

    private fun maybeResetHardRecoveryWindow(
        current: WatchdogState,
        now: Long,
    ): WatchdogState {
        if (current.hardRecoveryWindowStart > 0L &&
            now - current.hardRecoveryWindowStart >= WatchdogState.HARD_RECOVERY_WINDOW_MS
        ) {
            return current.copy(
                hardRecoveryCount = 0,
                hardRecoveryWindowStart = 0L,
            )
        }
        return current
    }

    private fun maybeResetCounters(current: WatchdogState, now: Long): WatchdogState {
        if (current.lastHealthyTimestamp > 0L &&
            now - current.lastHealthyTimestamp >= WatchdogState.HEALTHY_RESET_MS
        ) {
            return current.copy(
                softRecoveryCount = 0,
                mediumRecoveryCount = 0,
                hardRecoveryCount = 0,
                hardRecoveryWindowStart = 0L,
                currentBackoffMs = WatchdogState.INITIAL_BACKOFF_MS,
                consecutiveFailures = 0,
            )
        }
        return current
    }

    companion object {
        private const val TAG = "WatchdogManager"

        /** Consecutive-failure thresholds for escalation. */
        private const val MEDIUM_THRESHOLD = 3
        private const val HARD_THRESHOLD = 6

        internal fun recoveryLevel(consecutiveFailures: Int): RecoveryLevel = when {
            consecutiveFailures >= HARD_THRESHOLD -> RecoveryLevel.HARD
            consecutiveFailures >= MEDIUM_THRESHOLD -> RecoveryLevel.MEDIUM
            else -> RecoveryLevel.SOFT
        }

        internal fun nextBackoff(currentMs: Long): Long {
            val next = currentMs * 2
            return next.coerceAtMost(WatchdogState.MAX_BACKOFF_MS)
        }
    }

    /** Recovery escalation levels. */
    internal enum class RecoveryLevel { SOFT, MEDIUM, HARD }

    /**
     * Abstraction over [SystemClock] so tests can supply a deterministic clock.
     */
    fun interface Clock {
        fun elapsedRealtime(): Long
    }

    /** Default clock backed by [SystemClock.elapsedRealtime]. */
    internal object SystemClockImpl : Clock {
        override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()
    }
}
