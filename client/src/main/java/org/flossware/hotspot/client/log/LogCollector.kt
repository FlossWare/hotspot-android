package org.flossware.hotspot.client.log

import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A log entry captured by [LogCollector].
 */
data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val tag: String,
    val level: String,
    val message: String,
) {
    fun format(): String {
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        return "${df.format(Date(timestamp))} $level/$tag: $message"
    }
}

/**
 * Singleton that captures app log messages in a thread-safe ring buffer.
 *
 * Call the convenience methods ([i], [d], [w], [e]) instead of [android.util.Log]
 * directly. Each method stores the entry in the ring buffer *and* forwards
 * to [Timber] for structured output.
 *
 * The buffer holds the most recent [MAX_ENTRIES] (1000) entries. Older entries
 * are discarded when the limit is reached.
 */
object LogCollector {
    internal const val MAX_ENTRIES = 1000
    private val entries = ArrayDeque<LogEntry>(MAX_ENTRIES)
    private val lock = Any()

    fun i(tag: String, message: String): Int {
        addEntry(tag, "I", message)
        Timber.tag(tag).i(message)
        return 0
    }

    fun d(tag: String, message: String): Int {
        addEntry(tag, "D", message)
        Timber.tag(tag).d(message)
        return 0
    }

    fun w(tag: String, message: String): Int {
        addEntry(tag, "W", message)
        Timber.tag(tag).w(message)
        return 0
    }

    fun e(tag: String, message: String): Int {
        addEntry(tag, "E", message)
        Timber.tag(tag).e(message)
        return 0
    }

    fun e(tag: String, message: String, throwable: Throwable): Int {
        addEntry(tag, "E", "$message: ${throwable.message}")
        Timber.tag(tag).e(throwable, message)
        return 0
    }

    /**
     * Called by [StructuredTree] to record entries from Timber calls
     * without re-entering Timber (avoiding recursion).
     */
    internal fun record(tag: String, level: String, message: String) {
        addEntry(tag, level, message)
    }

    private fun addEntry(tag: String, level: String, message: String) {
        val entry = LogEntry(tag = tag, level = level, message = message)
        synchronized(lock) {
            if (entries.size >= MAX_ENTRIES) {
                entries.removeFirst()
            }
            entries.addLast(entry)
        }
    }

    fun getEntries(): List<LogEntry> {
        synchronized(lock) {
            return entries.toList()
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }

    internal val size: Int get() = synchronized(lock) { entries.size }
}
