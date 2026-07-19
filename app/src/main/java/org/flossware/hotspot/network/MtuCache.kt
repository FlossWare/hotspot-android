package org.flossware.hotspot.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Persists detected MTU values per (transport type, peer identifier) pair using
 * [SharedPreferences]. Cached values are reused on reconnect and automatically
 * invalidated after [MAX_AGE_MS] (30 days by default).
 *
 * Thread safety: All public methods synchronize on [lock] to ensure safe access
 * from multiple coroutines or threads.
 */
class MtuCache(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val lock = Any()

    /**
     * A cached MTU entry.
     *
     * @property mtu The detected MTU value.
     * @property timestampMs The wall-clock time when this entry was stored.
     */
    data class Entry(val mtu: Int, val timestampMs: Long)

    /**
     * Retrieves the cached MTU for the given transport and peer, or null if no
     * valid (non-expired) entry exists.
     *
     * @param transportType Transport identifier (e.g. "wifi_direct", "bluetooth", "usb").
     * @param peerIdentifier Peer address or identifier.
     * @return The cached MTU, or null if absent or expired.
     */
    fun get(transportType: String, peerIdentifier: String): Int? = synchronized(lock) {
        val key = buildKey(transportType, peerIdentifier)
        val mtu = prefs.getInt(mtuKey(key), 0)
        if (mtu == 0) return null

        val timestamp = prefs.getLong(timestampKey(key), 0L)
        if (isExpired(timestamp)) {
            Log.d(TAG, "Cache expired for $key, removing")
            remove(key)
            return null
        }

        Log.d(TAG, "Cache hit: $key -> $mtu")
        return mtu
    }

    /**
     * Stores an MTU value for the given transport and peer.
     *
     * @param transportType Transport identifier.
     * @param peerIdentifier Peer address or identifier.
     * @param mtu The MTU value to cache.
     */
    fun put(transportType: String, peerIdentifier: String, mtu: Int): Unit = synchronized(lock) {
        val key = buildKey(transportType, peerIdentifier)
        prefs.edit()
            .putInt(mtuKey(key), mtu)
            .putLong(timestampKey(key), System.currentTimeMillis())
            .apply()
        Log.d(TAG, "Cached: $key -> $mtu")
    }

    /**
     * Returns the full cache entry (MTU + timestamp) for inspection, or null if
     * no valid entry exists.
     */
    fun getEntry(transportType: String, peerIdentifier: String): Entry? = synchronized(lock) {
        val key = buildKey(transportType, peerIdentifier)
        val mtu = prefs.getInt(mtuKey(key), 0)
        if (mtu == 0) return null

        val timestamp = prefs.getLong(timestampKey(key), 0L)
        if (isExpired(timestamp)) {
            remove(key)
            return null
        }
        Entry(mtu, timestamp)
    }

    /**
     * Removes the cached entry for the given transport and peer.
     */
    fun invalidate(transportType: String, peerIdentifier: String): Unit = synchronized(lock) {
        remove(buildKey(transportType, peerIdentifier))
    }

    /**
     * Removes all cached MTU entries.
     */
    fun clear(): Unit = synchronized(lock) {
        prefs.edit().clear().apply()
        Log.d(TAG, "Cache cleared")
    }

    private fun remove(key: String) {
        prefs.edit()
            .remove(mtuKey(key))
            .remove(timestampKey(key))
            .apply()
    }

    private fun isExpired(timestampMs: Long): Boolean {
        return System.currentTimeMillis() - timestampMs > MAX_AGE_MS
    }

    companion object {
        private const val TAG = "MtuCache"
        internal const val PREFS_NAME = "mtu_cache"

        /** Cache entries expire after 30 days. */
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

        internal fun buildKey(transportType: String, peerIdentifier: String): String =
            "${transportType}:${peerIdentifier}"

        private fun mtuKey(key: String) = "mtu:$key"
        private fun timestampKey(key: String) = "ts:$key"
    }
}
