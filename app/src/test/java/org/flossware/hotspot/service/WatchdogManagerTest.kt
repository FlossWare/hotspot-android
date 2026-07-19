package org.flossware.hotspot.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.flossware.hotspot.model.HealthStatus
import org.flossware.hotspot.model.WatchdogState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchdogManagerTest {

    private var clockTime = 1_000_000L
    private val fakeClock = WatchdogManager.Clock { clockTime }

    private lateinit var manager: WatchdogManager

    private var softRecoveryCalls = 0
    private var mediumRecoveryCalls = 0
    private var hardRecoveryCalls = 0
    private var degradedModeCalls = 0

    private var proxyRunning = true
    private var wifiHealthy = true
    private var btHealthy = true
    private var usbHealthy = true

    @Before
    fun setUp() {
        softRecoveryCalls = 0
        mediumRecoveryCalls = 0
        hardRecoveryCalls = 0
        degradedModeCalls = 0
        proxyRunning = true
        wifiHealthy = true
        btHealthy = true
        usbHealthy = true
        clockTime = 1_000_000L

        manager = WatchdogManager(clock = fakeClock, healthCheckIntervalMs = 100L)
        manager.proxyRunningCheck = { proxyRunning }
        manager.wifiDirectHealthy = { wifiHealthy }
        manager.bluetoothHealthy = { btHealthy }
        manager.usbHealthy = { usbHealthy }
        manager.onSoftRecovery = { softRecoveryCalls++ }
        manager.onMediumRecovery = { mediumRecoveryCalls++ }
        manager.onHardRecovery = { hardRecoveryCalls++ }
        manager.onDegradedMode = { degradedModeCalls++ }
    }

    // -- Initial state --

    @Test
    fun `initial state is default WatchdogState`() {
        assertEquals(WatchdogState(), manager.state.value)
    }

    @Test
    fun `isRunning false before start`() {
        assertFalse(manager.isRunning)
    }

    // -- Start / Stop --

    @Test
    fun `start sets healthy state`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        manager.start(scope)

        assertEquals(HealthStatus.HEALTHY, manager.state.value.healthStatus)
        assertTrue(manager.isRunning)

        manager.stop()
    }

    @Test
    fun `stop resets state to default`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        manager.start(scope)
        manager.stop()

        assertEquals(WatchdogState(), manager.state.value)
        assertFalse(manager.isRunning)
    }

    @Test
    fun `double stop is safe`() {
        manager.stop()
        manager.stop()
        // No exception
    }

    // -- Health check: all healthy --

    @Test
    fun `healthy subsystems produce no recovery`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        manager.start(scope)

        manager.performHealthCheck()

        assertEquals(HealthStatus.HEALTHY, manager.state.value.healthStatus)
        assertEquals(0, softRecoveryCalls)
        assertEquals(0, mediumRecoveryCalls)
        assertEquals(0, hardRecoveryCalls)
        assertEquals(0, manager.state.value.consecutiveFailures)

        manager.stop()
    }

    // -- Soft recovery --

    @Test
    fun `proxy failure triggers soft recovery`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        manager.start(scope)

        proxyRunning = false
        manager.performHealthCheck()

        assertEquals(1, softRecoveryCalls)
        assertEquals(HealthStatus.RECOVERING, manager.state.value.healthStatus)
        assertEquals(1, manager.state.value.consecutiveFailures)
        assertEquals(1, manager.state.value.softRecoveryCount)

        manager.stop()
    }

    @Test
    fun `two consecutive failures still trigger soft recovery`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        manager.start(scope)

        proxyRunning = false
        manager.performHealthCheck()
        clockTime += 1000
        manager.performHealthCheck()

        assertEquals(2, softRecoveryCalls)
        assertEquals(2, manager.state.value.consecutiveFailures)

        manager.stop()
    }

    // -- Medium recovery escalation --

    @Test
    fun `three consecutive failures escalate to medium recovery`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        manager.start(scope)

        proxyRunning = false
        repeat(3) {
            clockTime += 1000
            manager.performHealthCheck()
        }

        // First 2 are soft, 3rd is medium
        assertEquals(2, softRecoveryCalls)
        assertEquals(1, mediumRecoveryCalls)
        assertEquals(3, manager.state.value.consecutiveFailures)

        manager.stop()
    }

    // -- Hard recovery escalation --

    @Test
    fun `six consecutive failures escalate to hard recovery`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        manager.start(scope)

        proxyRunning = false
        repeat(6) {
            clockTime += 1000
            manager.performHealthCheck()
        }

        // 1-2: soft (2), 3-5: medium (3), 6: hard (1)
        assertEquals(2, softRecoveryCalls)
        assertEquals(3, mediumRecoveryCalls)
        assertEquals(1, hardRecoveryCalls)
        assertEquals(1, manager.state.value.hardRecoveryCount)

        manager.stop()
    }

    // -- Degraded mode --

    @Test
    fun `degraded mode after max hard restarts per hour`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        manager.start(scope)

        proxyRunning = false

        // Each hard recovery requires 6 consecutive failures
        val checksPerHard = 6
        val totalHardNeeded = WatchdogState.MAX_HARD_RESTARTS_PER_HOUR

        repeat(checksPerHard * totalHardNeeded) {
            clockTime += 1000
            manager.performHealthCheck()
        }

        assertTrue(manager.state.value.isDegraded)
        assertEquals(HealthStatus.DEGRADED, manager.state.value.healthStatus)
        assertEquals(1, degradedModeCalls)

        manager.stop()
    }

    @Test
    fun `no further recovery in degraded mode`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        manager.start(scope)

        proxyRunning = false
        val checksPerHard = 6
        repeat(checksPerHard * WatchdogState.MAX_HARD_RESTARTS_PER_HOUR) {
            clockTime += 1000
            manager.performHealthCheck()
        }

        assertTrue(manager.state.value.isDegraded)
        val prevSoft = softRecoveryCalls
        val prevMedium = mediumRecoveryCalls
        val prevHard = hardRecoveryCalls

        // Additional checks should not trigger any recovery
        clockTime += 10_000
        manager.performHealthCheck()

        assertEquals(prevSoft, softRecoveryCalls)
        assertEquals(prevMedium, mediumRecoveryCalls)
        assertEquals(prevHard, hardRecoveryCalls)

        manager.stop()
    }

    // -- Recovery from failure --

    @Test
    fun `recovering to healthy resets consecutive failures`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        manager.start(scope)

        proxyRunning = false
        manager.performHealthCheck()
        assertEquals(1, manager.state.value.consecutiveFailures)

        proxyRunning = true
        clockTime += 1000
        manager.performHealthCheck()

        assertEquals(HealthStatus.HEALTHY, manager.state.value.healthStatus)
        assertEquals(0, manager.state.value.consecutiveFailures)
        assertEquals(WatchdogState.INITIAL_BACKOFF_MS, manager.state.value.currentBackoffMs)

        manager.stop()
    }

    // -- Backoff --

    @Test
    fun `backoff doubles with each failure`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        manager.start(scope)

        proxyRunning = false
        manager.performHealthCheck()
        assertEquals(2_000L, manager.state.value.currentBackoffMs)

        clockTime += 1000
        manager.performHealthCheck()
        assertEquals(4_000L, manager.state.value.currentBackoffMs)

        clockTime += 1000
        manager.performHealthCheck()
        assertEquals(8_000L, manager.state.value.currentBackoffMs)

        manager.stop()
    }

    @Test
    fun `backoff caps at MAX_BACKOFF_MS`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        manager.start(scope)

        proxyRunning = false
        // 1->2, 2->4, 4->8, 8->16, 16->32, 32->32 (capped)
        repeat(6) {
            clockTime += 1000
            manager.performHealthCheck()
        }
        assertEquals(WatchdogState.MAX_BACKOFF_MS, manager.state.value.currentBackoffMs)

        clockTime += 1000
        manager.performHealthCheck()
        assertEquals(WatchdogState.MAX_BACKOFF_MS, manager.state.value.currentBackoffMs)

        manager.stop()
    }

    // -- Hard recovery window reset --

    @Test
    fun `hard recovery count resets after one hour`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        manager.start(scope)

        proxyRunning = false
        // Trigger one hard recovery (6 failures)
        repeat(6) {
            clockTime += 1000
            manager.performHealthCheck()
        }
        assertEquals(1, manager.state.value.hardRecoveryCount)

        // Advance past the 1-hour window
        clockTime += WatchdogState.HARD_RECOVERY_WINDOW_MS + 1

        // Trigger another hard recovery
        proxyRunning = true
        manager.performHealthCheck() // mark healthy
        proxyRunning = false
        repeat(6) {
            clockTime += 1000
            manager.performHealthCheck()
        }
        // Count should be 1 again, not 2 (window reset)
        assertEquals(1, manager.state.value.hardRecoveryCount)

        manager.stop()
    }

    // -- Healthy reset (30 min) --

    @Test
    fun `counters reset after 30 minutes healthy`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        manager.start(scope)

        proxyRunning = false
        manager.performHealthCheck()
        assertEquals(1, manager.state.value.softRecoveryCount)

        proxyRunning = true
        clockTime += 1000
        manager.performHealthCheck()
        assertEquals(HealthStatus.HEALTHY, manager.state.value.healthStatus)

        // Advance 30 min
        clockTime += WatchdogState.HEALTHY_RESET_MS

        manager.performHealthCheck()
        assertEquals(0, manager.state.value.softRecoveryCount)
        assertEquals(0, manager.state.value.mediumRecoveryCount)
        assertEquals(0, manager.state.value.hardRecoveryCount)

        manager.stop()
    }

    // -- Subsystem check failure types --

    @Test
    fun `wifi failure triggers recovery`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        manager.start(scope)

        wifiHealthy = false
        manager.performHealthCheck()

        assertEquals(1, softRecoveryCalls)
        assertEquals(1, manager.state.value.consecutiveFailures)

        manager.stop()
    }

    @Test
    fun `bluetooth failure triggers recovery`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        manager.start(scope)

        btHealthy = false
        manager.performHealthCheck()

        assertEquals(1, softRecoveryCalls)

        manager.stop()
    }

    @Test
    fun `usb failure triggers recovery`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        manager.start(scope)

        usbHealthy = false
        manager.performHealthCheck()

        assertEquals(1, softRecoveryCalls)

        manager.stop()
    }

    @Test
    fun `subsystem check exception treated as unhealthy`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        manager.start(scope)

        manager.proxyRunningCheck = { throw RuntimeException("boom") }
        manager.performHealthCheck()

        assertEquals(1, softRecoveryCalls)
        assertEquals(HealthStatus.RECOVERING, manager.state.value.healthStatus)

        manager.stop()
    }

    // -- Static helpers --

    @Test
    fun `recoveryLevel returns SOFT for low failure counts`() {
        assertEquals(
            WatchdogManager.RecoveryLevel.SOFT,
            WatchdogManager.recoveryLevel(1),
        )
        assertEquals(
            WatchdogManager.RecoveryLevel.SOFT,
            WatchdogManager.recoveryLevel(2),
        )
    }

    @Test
    fun `recoveryLevel returns MEDIUM for mid failure counts`() {
        assertEquals(
            WatchdogManager.RecoveryLevel.MEDIUM,
            WatchdogManager.recoveryLevel(3),
        )
        assertEquals(
            WatchdogManager.RecoveryLevel.MEDIUM,
            WatchdogManager.recoveryLevel(5),
        )
    }

    @Test
    fun `recoveryLevel returns HARD for high failure counts`() {
        assertEquals(
            WatchdogManager.RecoveryLevel.HARD,
            WatchdogManager.recoveryLevel(6),
        )
        assertEquals(
            WatchdogManager.RecoveryLevel.HARD,
            WatchdogManager.recoveryLevel(100),
        )
    }

    @Test
    fun `nextBackoff doubles the value`() {
        assertEquals(2_000L, WatchdogManager.nextBackoff(1_000L))
        assertEquals(4_000L, WatchdogManager.nextBackoff(2_000L))
        assertEquals(8_000L, WatchdogManager.nextBackoff(4_000L))
    }

    @Test
    fun `nextBackoff caps at MAX_BACKOFF_MS`() {
        assertEquals(WatchdogState.MAX_BACKOFF_MS, WatchdogManager.nextBackoff(16_000L))
        assertEquals(WatchdogState.MAX_BACKOFF_MS, WatchdogManager.nextBackoff(32_000L))
        assertEquals(WatchdogState.MAX_BACKOFF_MS, WatchdogManager.nextBackoff(64_000L))
    }

    // -- Timestamp tracking --

    @Test
    fun `lastCheckTimestamp updates on each check`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        manager.start(scope)

        clockTime = 5_000_000L
        manager.performHealthCheck()
        assertEquals(5_000_000L, manager.state.value.lastCheckTimestamp)

        clockTime = 5_010_000L
        manager.performHealthCheck()
        assertEquals(5_010_000L, manager.state.value.lastCheckTimestamp)

        manager.stop()
    }

    @Test
    fun `lastHealthyTimestamp updates when healthy`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        manager.start(scope)

        clockTime = 2_000_000L
        manager.performHealthCheck()
        assertEquals(2_000_000L, manager.state.value.lastHealthyTimestamp)

        // Make unhealthy
        proxyRunning = false
        clockTime = 3_000_000L
        manager.performHealthCheck()
        // lastHealthyTimestamp should NOT update
        assertTrue(manager.state.value.lastHealthyTimestamp < 3_000_000L)

        manager.stop()
    }
}
