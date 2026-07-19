package org.flossware.hotspot.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchdogStateTest {

    @Test
    fun `default state has correct values`() {
        val state = WatchdogState()
        assertEquals(HealthStatus.HEALTHY, state.healthStatus)
        assertEquals(0, state.softRecoveryCount)
        assertEquals(0, state.mediumRecoveryCount)
        assertEquals(0, state.hardRecoveryCount)
        assertFalse(state.isDegraded)
        assertEquals(0L, state.lastCheckTimestamp)
        assertEquals(0L, state.lastHealthyTimestamp)
        assertEquals(0L, state.hardRecoveryWindowStart)
        assertEquals(WatchdogState.INITIAL_BACKOFF_MS, state.currentBackoffMs)
        assertEquals(0, state.consecutiveFailures)
    }

    @Test
    fun `INITIAL_BACKOFF_MS is 1 second`() {
        assertEquals(1_000L, WatchdogState.INITIAL_BACKOFF_MS)
    }

    @Test
    fun `MAX_BACKOFF_MS is 32 seconds`() {
        assertEquals(32_000L, WatchdogState.MAX_BACKOFF_MS)
    }

    @Test
    fun `MAX_HARD_RESTARTS_PER_HOUR is 5`() {
        assertEquals(5, WatchdogState.MAX_HARD_RESTARTS_PER_HOUR)
    }

    @Test
    fun `HEALTHY_RESET_MS is 30 minutes`() {
        assertEquals(30L * 60 * 1_000, WatchdogState.HEALTHY_RESET_MS)
    }

    @Test
    fun `HARD_RECOVERY_WINDOW_MS is 1 hour`() {
        assertEquals(60L * 60 * 1_000, WatchdogState.HARD_RECOVERY_WINDOW_MS)
    }

    @Test
    fun `HEALTH_CHECK_INTERVAL_MS is 10 seconds`() {
        assertEquals(10_000L, WatchdogState.HEALTH_CHECK_INTERVAL_MS)
    }

    @Test
    fun `copy preserves fields correctly`() {
        val state = WatchdogState(
            healthStatus = HealthStatus.RECOVERING,
            softRecoveryCount = 2,
            mediumRecoveryCount = 1,
            hardRecoveryCount = 3,
            isDegraded = false,
            lastCheckTimestamp = 1000L,
            lastHealthyTimestamp = 500L,
            hardRecoveryWindowStart = 200L,
            currentBackoffMs = 4_000L,
            consecutiveFailures = 5,
        )
        val copied = state.copy(healthStatus = HealthStatus.DEGRADED, isDegraded = true)
        assertEquals(HealthStatus.DEGRADED, copied.healthStatus)
        assertTrue(copied.isDegraded)
        assertEquals(2, copied.softRecoveryCount)
        assertEquals(1, copied.mediumRecoveryCount)
        assertEquals(3, copied.hardRecoveryCount)
        assertEquals(1000L, copied.lastCheckTimestamp)
        assertEquals(500L, copied.lastHealthyTimestamp)
        assertEquals(200L, copied.hardRecoveryWindowStart)
        assertEquals(4_000L, copied.currentBackoffMs)
        assertEquals(5, copied.consecutiveFailures)
    }

    @Test
    fun `equality works for identical states`() {
        val a = WatchdogState(healthStatus = HealthStatus.RECOVERING, softRecoveryCount = 3)
        val b = WatchdogState(healthStatus = HealthStatus.RECOVERING, softRecoveryCount = 3)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `inequality for different states`() {
        val a = WatchdogState(softRecoveryCount = 1)
        val b = WatchdogState(softRecoveryCount = 2)
        assertFalse(a == b)
    }

    @Test
    fun `all HealthStatus values are present`() {
        val values = HealthStatus.entries
        assertEquals(3, values.size)
        assertTrue(values.contains(HealthStatus.HEALTHY))
        assertTrue(values.contains(HealthStatus.RECOVERING))
        assertTrue(values.contains(HealthStatus.DEGRADED))
    }
}
