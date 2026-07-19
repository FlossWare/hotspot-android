package org.flossware.hotspot.metrics

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Debug
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.RandomAccessFile

/**
 * Coroutine-based collector that periodically samples system metrics and
 * writes them into [PerformanceMetrics].
 *
 * Samples CPU utilization every [SAMPLE_INTERVAL_MS] (10 seconds) by reading
 * `/proc/self/stat`. Memory is read from [Runtime] (heap) and [Debug] (native).
 * Battery consumption is tracked via [BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER].
 *
 * Designed for minimal overhead: one lightweight coroutine, no allocations in
 * the hot path beyond the proc file read.
 */
class MetricsCollector(private val context: Context) {

    private var job: Job? = null
    private var prevCpuTime: Long = 0L
    private var prevWallTime: Long = 0L
    private var initialChargeUah: Int = -1

    /**
     * Starts periodic metric collection in the given [scope].
     * Safe to call multiple times; subsequent calls are no-ops while running.
     */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return

        prevCpuTime = getProcessCpuTime()
        prevWallTime = System.nanoTime()
        initialChargeUah = getCurrentChargeUah()

        job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(SAMPLE_INTERVAL_MS)
                try {
                    sampleCpu()
                    sampleMemory()
                    sampleBattery()
                } catch (e: Exception) {
                    Log.w(TAG, "Metrics sample failed: ${e.message}")
                }
            }
        }
        Log.d(TAG, "MetricsCollector started")
    }

    /**
     * Stops the collector. Safe to call if not started.
     */
    fun stop() {
        job?.cancel()
        job = null
        Log.d(TAG, "MetricsCollector stopped")
    }

    val isRunning: Boolean get() = job?.isActive == true

    // ===== Sampling =====

    internal fun sampleCpu() {
        val currentCpuTime = getProcessCpuTime()
        val currentWallTime = System.nanoTime()

        val cpuDelta = currentCpuTime - prevCpuTime
        val wallDelta = currentWallTime - prevWallTime

        if (wallDelta > 0) {
            // cpuDelta is in clock ticks (typically 10ms each = centiseconds).
            // Convert to nanoseconds: ticks * (1_000_000_000 / clockTicksPerSecond).
            val cpuNanos = cpuDelta * NANOS_PER_TICK
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val utilization = (cpuNanos.toFloat() / (wallDelta * cores)).coerceIn(0f, 1f)
            PerformanceMetrics.cpuUtilization = utilization
        }

        prevCpuTime = currentCpuTime
        prevWallTime = currentWallTime
    }

    internal fun sampleMemory() {
        val runtime = Runtime.getRuntime()
        PerformanceMetrics.heapMaxBytes = runtime.maxMemory()
        PerformanceMetrics.heapUsedBytes = runtime.totalMemory() - runtime.freeMemory()
        PerformanceMetrics.nativeHeapBytes = Debug.getNativeHeapAllocatedSize()
    }

    internal fun sampleBattery() {
        if (initialChargeUah < 0) return
        val currentUah = getCurrentChargeUah()
        if (currentUah < 0) return
        val consumedUah = initialChargeUah - currentUah
        if (consumedUah >= 0) {
            PerformanceMetrics.batteryConsumedMah = consumedUah / 1000f
        }
    }

    // ===== Helpers =====

    /**
     * Reads the process CPU time from /proc/self/stat.
     * Returns the sum of utime + stime in clock ticks.
     */
    private fun getProcessCpuTime(): Long {
        return try {
            RandomAccessFile("/proc/self/stat", "r").use { file ->
                val line = file.readLine() ?: return 0L
                // Fields are space-separated. utime is field 14, stime is field 15 (1-indexed).
                // The comm field (2) may contain spaces and is enclosed in parentheses.
                val afterComm = line.substringAfter(") ")
                val fields = afterComm.split(" ")
                // After stripping "(comm) ", field indices shift:
                // state=0, ppid=1, ... utime=11, stime=12
                val utime = fields.getOrNull(UTIME_INDEX)?.toLongOrNull() ?: 0L
                val stime = fields.getOrNull(STIME_INDEX)?.toLongOrNull() ?: 0L
                utime + stime
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read /proc/self/stat: ${e.message}")
            0L
        }
    }

    private fun getCurrentChargeUah(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return -1
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
    }

    companion object {
        private const val TAG = "MetricsCollector"
        internal const val SAMPLE_INTERVAL_MS = 10_000L

        // In /proc/self/stat after "(comm) ", utime is at index 11, stime at 12
        private const val UTIME_INDEX = 11
        private const val STIME_INDEX = 12

        // Linux clock ticks are typically 100 Hz (10ms per tick)
        private const val CLOCK_TICKS_PER_SECOND = 100L
        private const val NANOS_PER_TICK = 1_000_000_000L / CLOCK_TICKS_PER_SECOND
    }
}
