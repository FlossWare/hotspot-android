package org.flossware.hotspot.compat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests for foreground service survival under Doze and OEM battery optimizations.
 *
 * These tests verify that the system services needed for FlossWare Hotspot's
 * foreground service are available and behave correctly. They do not start
 * the actual HotspotService (which requires permissions granted at runtime).
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BackgroundServiceCompatTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @Test
    fun powerManagerAvailable() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        assertNotNull("PowerManager should be available", pm)
    }

    @Test
    fun partialWakeLockCanBeCreated() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "FlossHotspot::CompatTest",
        )
        assertNotNull("PARTIAL_WAKE_LOCK should be creatable", wakeLock)

        // Acquire and immediately release to verify no OEM restrictions prevent it
        wakeLock.acquire(WAKELOCK_TEST_TIMEOUT_MS)
        assertTrue("WakeLock should be held after acquire", wakeLock.isHeld)
        wakeLock.release()
        assertFalse("WakeLock should not be held after release", wakeLock.isHeld)
    }

    @Test
    fun notificationChannelCanBeCreated() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNotNull("NotificationManager should be available", nm)

        val channelId = "compat_test_channel"
        val channel = NotificationChannel(
            channelId,
            "Compat Test",
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(channel)

        val created = nm.getNotificationChannel(channelId)
        assertNotNull(
            "NotificationChannel should be retrievable after creation",
            created,
        )

        // Cleanup
        nm.deleteNotificationChannel(channelId)
    }

    @Test
    fun deviceReportsDozeSupport() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        // isDeviceIdleMode() should be callable. On FTL devices in active use,
        // it will return false. On real devices under Doze, it returns true.
        // Either value is acceptable -- we verify the API is available.
        var didThrow = false
        try {
            pm.isDeviceIdleMode
        } catch (e: Exception) {
            didThrow = true
        }

        assertFalse(
            "PowerManager.isDeviceIdleMode should be callable without exception",
            didThrow,
        )
    }

    @Test
    fun batteryOptimizationQueryAvailable() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        // Check whether the app is on the battery optimization whitelist.
        // On FTL, test apps may or may not be whitelisted.
        var didThrow = false
        var isWhitelisted = false
        try {
            isWhitelisted = pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            didThrow = true
        }

        assertFalse(
            "isIgnoringBatteryOptimizations() should be callable (whitelisted=$isWhitelisted)",
            didThrow,
        )
    }

    @Test
    fun foregroundServiceTypeSupported() {
        // API 29+ supports foreground service types. Our manifest declares
        // foregroundServiceType="connectedDevice". Verify the constant is available.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            assertTrue(
                "FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE should be a valid constant",
                type > 0,
            )
        }
    }

    companion object {
        private const val WAKELOCK_TEST_TIMEOUT_MS = 1000L
    }
}
