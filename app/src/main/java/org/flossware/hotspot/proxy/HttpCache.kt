package org.flossware.hotspot.proxy

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger

/**
 * Simple HTTP response cache for plaintext HTTP (port 80) traffic only.
 *
 * This cache operates within a SOCKS5 proxy and can only cache responses to
 * plaintext HTTP requests. HTTPS traffic (port 443) passes through the proxy
 * as an opaque tunnel via CONNECT, so it cannot be inspected or cached.
 * HTTP/2 connections are always over TLS and are therefore also not cacheable.
 *
 * In modern web traffic, most connections use HTTPS, so this cache has limited
 * applicability. It remains useful for:
 * - Captive portal detection pages (e.g. connectivity checks)
 * - Some IoT device traffic
 * - Legacy HTTP-only sites
 *
 * Caching rules:
 * - Only GET requests with HTTP 200 OK responses are cached
 * - Responses with Cache-Control: no-store, no-cache, or private are not cached
 * - Responses with Set-Cookie headers are not cached
 * - Responses with Vary headers are not cached (request header variations not tracked)
 * - Requests with Authorization headers bypass the cache entirely
 * - Pragma: no-cache is respected in both requests and responses
 * - max-age=0 responses are not cached (immediately stale)
 * - Only text, application/javascript, application/json, and image content types are cached
 * - Individual entries are limited to [maxEntryBytes] (default 5 MB)
 * - Total cache size is limited to [maxTotalBytes] (default 50 MB)
 * - Content-Encoding (gzip, br) responses are stored and served as-is since
 *   the original response headers are preserved
 */
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

        // Don't serve from cache for authenticated requests
        if (containsHeader(requestHeaders, "authorization")) return false

        // Respect Pragma: no-cache in the request
        if (headerContains(requestHeaders, "pragma", "no-cache")) {
            _misses.incrementAndGet()
            return false
        }

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
        requestHeaders: String = "",
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
        var hasExplicitMaxAge = false

        // Don't cache responses to requests with Authorization headers
        if (containsHeader(requestHeaders, "authorization")) cacheable = false

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
                    if ("no-store" in cc || "no-cache" in cc || "private" in cc) cacheable = false
                    val maMatch = MAX_AGE_REGEX.find(cc)
                    if (maMatch != null) {
                        maxAge = maMatch.groupValues[1].toInt()
                        hasExplicitMaxAge = true
                    }
                }
                lower.startsWith("set-cookie:") -> cacheable = false
                lower.startsWith("pragma:") -> {
                    if ("no-cache" in lower.substringAfter(":")) cacheable = false
                }
                lower.startsWith("vary:") -> cacheable = false
            }
        }
        clientOutput.flush()

        // Don't cache if max-age=0 (response is immediately stale)
        if (hasExplicitMaxAge && maxAge <= 0) cacheable = false

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
        var ioError = false
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
            ioError = true
        }

        val body = bodyBuffer.toByteArray()
        if (!ioError && body.isNotEmpty() && body.size <= maxEntryBytes) {
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
            if (entries.remove(oldest.key, oldest.value)) {
                currentSize.addAndGet(-oldest.value.body.size.toLong())
            }
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
        const val MAX_LINE_LENGTH = 8192
        private val CRLF = "\r\n".toByteArray()
        private val MAX_AGE_REGEX = Regex("""max-age\s*=\s*(\d+)""")

        internal fun readLine(input: InputStream, maxLength: Int = MAX_LINE_LENGTH): String? {
            val sb = StringBuilder()
            while (true) {
                if (sb.length >= maxLength) throw IOException("HTTP line exceeds max length")
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

        /**
         * Check if a header with the given name is present (case-insensitive).
         * Headers are expected in "Name: value\r\n" format.
         */
        internal fun containsHeader(headers: String, name: String): Boolean {
            val prefix = "$name:"
            return headers.lineSequence().any {
                it.trim().startsWith(prefix, ignoreCase = true)
            }
        }

        /**
         * Check if a header with the given name contains a specific value (case-insensitive).
         */
        internal fun headerContains(headers: String, name: String, value: String): Boolean {
            val prefix = "$name:"
            return headers.lineSequence().any { line ->
                val trimmed = line.trim()
                trimmed.startsWith(prefix, ignoreCase = true) &&
                    trimmed.substringAfter(":").contains(value, ignoreCase = true)
            }
        }
    }
}
