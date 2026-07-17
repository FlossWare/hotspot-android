package org.flossware.hotspot.proxy

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger

class HttpCache(
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    private val maxEntryBytes: Int = DEFAULT_MAX_ENTRY_BYTES,
) {
    private val entries = ConcurrentHashMap<String, CacheEntry>()
    private val currentSize = AtomicLong(0)
    private val _hits = AtomicLong(0)
    private val _misses = AtomicLong(0)
    private val _dataSaved = AtomicLong(0)

    val hits: Long get() = _hits.get()
    val misses: Long get() = _misses.get()
    val dataSaved: Long get() = _dataSaved.get()

    fun tryServeFromCache(
        host: String,
        requestLine: String,
        requestHeaders: String,
        clientOutput: OutputStream,
    ): Boolean {
        val method = requestLine.substringBefore(' ')
        if (method != "GET") return false

        val path = requestLine.substringAfter(' ').substringBefore(' ')
        val key = "GET $host$path"
        val entry = entries[key] ?: run {
            _misses.incrementAndGet()
            return false
        }

        if (entry.isExpired()) {
            if (entries.remove(key, entry)) {
                currentSize.addAndGet(-entry.body.size.toLong())
            }
            _misses.incrementAndGet()
            return false
        }

        _hits.incrementAndGet()
        _dataSaved.addAndGet(entry.body.size.toLong())

        clientOutput.write(entry.statusLine.toByteArray())
        clientOutput.write(CRLF)
        clientOutput.write(entry.responseHeaders.toByteArray())
        clientOutput.write(CRLF)
        clientOutput.write(entry.body)
        clientOutput.flush()
        return true
    }

    fun cacheResponse(
        host: String,
        requestLine: String,
        responseStream: InputStream,
        clientOutput: OutputStream,
        upstreamOutput: OutputStream,
        clientInput: InputStream,
    ) {
        val method = requestLine.substringBefore(' ')
        val path = requestLine.substringAfter(' ').substringBefore(' ')
        val key = "GET $host$path"

        val statusLine = readLine(responseStream) ?: return
        clientOutput.write(statusLine.toByteArray())
        clientOutput.write(CRLF)

        if (!statusLine.contains(" 200 ")) {
            relay(responseStream, clientOutput)
            return
        }

        val headers = StringBuilder()
        var contentLength = -1
        var cacheable = method == "GET"
        var contentType = ""
        var maxAge = DEFAULT_MAX_AGE

        while (true) {
            val line = readLine(responseStream) ?: break
            headers.append(line).append("\r\n")
            clientOutput.write(line.toByteArray())
            clientOutput.write(CRLF)
            if (line.isEmpty()) break

            val lower = line.lowercase()
            when {
                lower.startsWith("content-length:") ->
                    contentLength = lower.substringAfter(":").trim().toIntOrNull() ?: -1
                lower.startsWith("content-type:") ->
                    contentType = lower.substringAfter(":").trim()
                lower.startsWith("cache-control:") -> {
                    val cc = lower.substringAfter(":").trim()
                    if ("no-store" in cc || "private" in cc) cacheable = false
                    val maMatch = Regex("max-age=(\\d+)").find(cc)
                    if (maMatch != null) maxAge = maMatch.groupValues[1].toInt()
                }
                lower.startsWith("set-cookie:") -> cacheable = false
            }
        }
        clientOutput.flush()

        if (!cacheable || contentLength > maxEntryBytes || contentLength == 0) {
            relay(responseStream, clientOutput)
            return
        }

        if (!isCacheableContentType(contentType)) {
            relay(responseStream, clientOutput)
            return
        }

        val bodyBuffer = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var totalRead = 0
        try {
            while (true) {
                val n = responseStream.read(buf)
                if (n == -1) break
                clientOutput.write(buf, 0, n)
                clientOutput.flush()
                bodyBuffer.write(buf, 0, n)
                totalRead += n
                if (totalRead > maxEntryBytes) {
                    relay(responseStream, clientOutput)
                    return
                }
            }
        } catch (_: IOException) {
        }

        val body = bodyBuffer.toByteArray()
        if (body.isNotEmpty() && body.size <= maxEntryBytes) {
            evictIfNeeded(body.size.toLong())
            val newEntry = CacheEntry(
                statusLine = statusLine,
                responseHeaders = headers.toString(),
                body = body,
                expiresAt = System.currentTimeMillis() + maxAge * 1000L,
            )
            val old = entries.put(key, newEntry)
            if (old != null) currentSize.addAndGet(-old.body.size.toLong())
            currentSize.addAndGet(body.size.toLong())
        }
    }

    private fun isCacheableContentType(contentType: String): Boolean {
        if (contentType.isEmpty()) return true
        return contentType.startsWith("text/") ||
            contentType.startsWith("application/javascript") ||
            contentType.startsWith("application/json") ||
            contentType.startsWith("image/")
    }

    private fun evictIfNeeded(neededBytes: Long) {
        while (currentSize.get() + neededBytes > maxTotalBytes && entries.isNotEmpty()) {
            val oldest = entries.entries.minByOrNull { it.value.expiresAt } ?: break
            entries.remove(oldest.key)
            currentSize.addAndGet(-oldest.value.body.size.toLong())
        }
    }

    fun clear() {
        entries.clear()
        currentSize.set(0)
    }

    internal data class CacheEntry(
        val statusLine: String,
        val responseHeaders: String,
        val body: ByteArray,
        val expiresAt: Long,
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() >= expiresAt

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CacheEntry) return false
            return statusLine == other.statusLine && body.contentEquals(other.body) &&
                expiresAt == other.expiresAt
        }

        override fun hashCode(): Int =
            statusLine.hashCode() * 31 + body.contentHashCode() * 31 + expiresAt.hashCode()
    }

    companion object {
        private val log = Logger.getLogger(HttpCache::class.java.name)
        const val DEFAULT_MAX_TOTAL_BYTES = 50L * 1024 * 1024 // 50 MB
        const val DEFAULT_MAX_ENTRY_BYTES = 5 * 1024 * 1024 // 5 MB
        const val DEFAULT_MAX_AGE = 3600
        private val CRLF = "\r\n".toByteArray()

        internal fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b == -1) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\r'.code) {
                    val next = input.read()
                    if (next == '\n'.code) return sb.toString()
                    sb.append('\r')
                    if (next != -1) sb.append(next.toChar())
                } else if (b == '\n'.code) {
                    return sb.toString()
                } else {
                    sb.append(b.toChar())
                }
            }
        }

        private fun relay(input: InputStream, output: OutputStream) {
            val buf = ByteArray(8192)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    output.write(buf, 0, n)
                    output.flush()
                }
            } catch (_: IOException) {
            }
        }
    }
}
