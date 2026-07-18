package org.flossware.hotspot.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class HttpCacheTest {

    private lateinit var cache: HttpCache

    @Before
    fun setUp() {
        cache = HttpCache(maxTotalBytes = 1024 * 1024, maxEntryBytes = 256 * 1024)
    }

    @Test
    fun `cache miss returns false`() {
        val output = ByteArrayOutputStream()
        val result = cache.tryServeFromCache("example.com", "GET / HTTP/1.1", "", output)
        assertFalse(result)
        assertEquals(0L, cache.hits)
        assertEquals(1L, cache.misses)
    }

    @Test
    fun `non-GET requests are never cached`() {
        val output = ByteArrayOutputStream()
        val result = cache.tryServeFromCache("example.com", "POST / HTTP/1.1", "", output)
        assertFalse(result)
        assertEquals(0L, cache.hits)
        assertEquals(0L, cache.misses)
    }

    @Test
    fun `cache stores and serves GET 200 response`() {
        val responseBody = "Hello, World!"
        val responseStream = buildHttpResponse(200, responseBody)
        val clientOutput = ByteArrayOutputStream()
        val upstreamOutput = ByteArrayOutputStream()
        val clientInput = ByteArrayInputStream(ByteArray(0))

        cache.cacheResponse(
            "example.com", "GET /index.html HTTP/1.1",
            responseStream, clientOutput, upstreamOutput, clientInput,
        )

        val serveOutput = ByteArrayOutputStream()
        val hit = cache.tryServeFromCache("example.com", "GET /index.html HTTP/1.1", "", serveOutput)
        assertTrue(hit)
        assertEquals(1L, cache.hits)
        assertTrue(serveOutput.toString().contains("Hello, World!"))
    }

    @Test
    fun `cache key includes host and path`() {
        val responseStream = buildHttpResponse(200, "body1")
        val clientOutput = ByteArrayOutputStream()

        cache.cacheResponse(
            "a.com", "GET /page HTTP/1.1",
            responseStream, clientOutput, ByteArrayOutputStream(), ByteArrayInputStream(ByteArray(0)),
        )

        val output1 = ByteArrayOutputStream()
        assertTrue(cache.tryServeFromCache("a.com", "GET /page HTTP/1.1", "", output1))

        val output2 = ByteArrayOutputStream()
        assertFalse(cache.tryServeFromCache("b.com", "GET /page HTTP/1.1", "", output2))

        val output3 = ByteArrayOutputStream()
        assertFalse(cache.tryServeFromCache("a.com", "GET /other HTTP/1.1", "", output3))
    }

    @Test
    fun `no-store prevents caching`() {
        val response = "HTTP/1.1 200 OK\r\nCache-Control: no-store\r\nContent-Length: 4\r\n\r\ntest"
        val responseStream = ByteArrayInputStream(response.toByteArray())
        val clientOutput = ByteArrayOutputStream()

        cache.cacheResponse(
            "example.com", "GET / HTTP/1.1",
            responseStream, clientOutput, ByteArrayOutputStream(), ByteArrayInputStream(ByteArray(0)),
        )

        val serveOutput = ByteArrayOutputStream()
        assertFalse(cache.tryServeFromCache("example.com", "GET / HTTP/1.1", "", serveOutput))
    }

    @Test
    fun `private prevents caching`() {
        val response = "HTTP/1.1 200 OK\r\nCache-Control: private\r\nContent-Length: 4\r\n\r\ntest"
        val responseStream = ByteArrayInputStream(response.toByteArray())
        val clientOutput = ByteArrayOutputStream()

        cache.cacheResponse(
            "example.com", "GET / HTTP/1.1",
            responseStream, clientOutput, ByteArrayOutputStream(), ByteArrayInputStream(ByteArray(0)),
        )

        val serveOutput = ByteArrayOutputStream()
        assertFalse(cache.tryServeFromCache("example.com", "GET / HTTP/1.1", "", serveOutput))
    }

    @Test
    fun `set-cookie prevents caching`() {
        val response = "HTTP/1.1 200 OK\r\nSet-Cookie: sid=abc\r\nContent-Length: 4\r\n\r\ntest"
        val responseStream = ByteArrayInputStream(response.toByteArray())
        val clientOutput = ByteArrayOutputStream()

        cache.cacheResponse(
            "example.com", "GET / HTTP/1.1",
            responseStream, clientOutput, ByteArrayOutputStream(), ByteArrayInputStream(ByteArray(0)),
        )

        val serveOutput = ByteArrayOutputStream()
        assertFalse(cache.tryServeFromCache("example.com", "GET / HTTP/1.1", "", serveOutput))
    }

    @Test
    fun `non-200 responses are not cached`() {
        val response = "HTTP/1.1 404 Not Found\r\nContent-Length: 9\r\n\r\nnot found"
        val responseStream = ByteArrayInputStream(response.toByteArray())
        val clientOutput = ByteArrayOutputStream()

        cache.cacheResponse(
            "example.com", "GET /missing HTTP/1.1",
            responseStream, clientOutput, ByteArrayOutputStream(), ByteArrayInputStream(ByteArray(0)),
        )

        val serveOutput = ByteArrayOutputStream()
        assertFalse(cache.tryServeFromCache("example.com", "GET /missing HTTP/1.1", "", serveOutput))
    }

    @Test
    fun `data saved tracks cached bytes`() {
        val body = "a".repeat(100)
        val responseStream = buildHttpResponse(200, body)
        val clientOutput = ByteArrayOutputStream()

        cache.cacheResponse(
            "example.com", "GET / HTTP/1.1",
            responseStream, clientOutput, ByteArrayOutputStream(), ByteArrayInputStream(ByteArray(0)),
        )

        assertEquals(0L, cache.dataSaved)

        val serveOutput = ByteArrayOutputStream()
        cache.tryServeFromCache("example.com", "GET / HTTP/1.1", "", serveOutput)
        assertTrue(cache.dataSaved > 0)
    }

    @Test
    fun `clear removes all entries`() {
        val responseStream = buildHttpResponse(200, "test")
        val clientOutput = ByteArrayOutputStream()

        cache.cacheResponse(
            "example.com", "GET / HTTP/1.1",
            responseStream, clientOutput, ByteArrayOutputStream(), ByteArrayInputStream(ByteArray(0)),
        )

        cache.clear()

        val serveOutput = ByteArrayOutputStream()
        assertFalse(cache.tryServeFromCache("example.com", "GET / HTTP/1.1", "", serveOutput))
    }

    @Test
    fun `evicts oldest when total size exceeded`() {
        val smallCache = HttpCache(maxTotalBytes = 200, maxEntryBytes = 150)

        val body1 = "a".repeat(100)
        smallCache.cacheResponse(
            "a.com", "GET / HTTP/1.1",
            buildHttpResponse(200, body1), ByteArrayOutputStream(),
            ByteArrayOutputStream(), ByteArrayInputStream(ByteArray(0)),
        )

        val body2 = "b".repeat(120)
        smallCache.cacheResponse(
            "b.com", "GET / HTTP/1.1",
            buildHttpResponse(200, body2), ByteArrayOutputStream(),
            ByteArrayOutputStream(), ByteArrayInputStream(ByteArray(0)),
        )

        val out2 = ByteArrayOutputStream()
        assertTrue(smallCache.tryServeFromCache("b.com", "GET / HTTP/1.1", "", out2))
    }

    @Test
    fun `entry too large is not cached`() {
        val tinyCache = HttpCache(maxTotalBytes = 1024 * 1024, maxEntryBytes = 10)

        val body = "a".repeat(100)
        tinyCache.cacheResponse(
            "example.com", "GET / HTTP/1.1",
            buildHttpResponse(200, body, contentLength = 100), ByteArrayOutputStream(),
            ByteArrayOutputStream(), ByteArrayInputStream(ByteArray(0)),
        )

        val out = ByteArrayOutputStream()
        assertFalse(tinyCache.tryServeFromCache("example.com", "GET / HTTP/1.1", "", out))
    }

    @Test
    fun `readLine parses CRLF lines`() {
        val data = "Hello\r\nWorld\r\n".toByteArray()
        val input = ByteArrayInputStream(data)
        assertEquals("Hello", HttpCache.readLine(input))
        assertEquals("World", HttpCache.readLine(input))
    }

    @Test
    fun `readLine parses LF-only lines`() {
        val data = "Hello\nWorld\n".toByteArray()
        val input = ByteArrayInputStream(data)
        assertEquals("Hello", HttpCache.readLine(input))
        assertEquals("World", HttpCache.readLine(input))
    }

    @Test
    fun `readLine returns null on empty stream`() {
        val input = ByteArrayInputStream(ByteArray(0))
        assertEquals(null, HttpCache.readLine(input))
    }

    @Test
    fun `cacheable content types are accepted`() {
        for (ct in listOf("text/html", "text/css", "application/javascript", "application/json", "image/png")) {
            val response = "HTTP/1.1 200 OK\r\nContent-Type: $ct\r\nContent-Length: 4\r\n\r\ntest"
            val responseStream = ByteArrayInputStream(response.toByteArray())
            val key = "test-$ct"
            cache.cacheResponse(
                "$key.com", "GET / HTTP/1.1",
                responseStream, ByteArrayOutputStream(), ByteArrayOutputStream(), ByteArrayInputStream(ByteArray(0)),
            )
            val out = ByteArrayOutputStream()
            assertTrue("$ct should be cacheable", cache.tryServeFromCache("$key.com", "GET / HTTP/1.1", "", out))
        }
    }

    @Test
    fun `non-cacheable content types are rejected`() {
        val response = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: 4\r\n\r\ntest"
        val responseStream = ByteArrayInputStream(response.toByteArray())
        cache.cacheResponse(
            "bin.com", "GET / HTTP/1.1",
            responseStream, ByteArrayOutputStream(), ByteArrayOutputStream(), ByteArrayInputStream(ByteArray(0)),
        )
        val out = ByteArrayOutputStream()
        assertFalse(cache.tryServeFromCache("bin.com", "GET / HTTP/1.1", "", out))
    }

    @Test
    fun `companion constants are correct`() {
        assertEquals(50L * 1024 * 1024, HttpCache.DEFAULT_MAX_TOTAL_BYTES)
        assertEquals(5 * 1024 * 1024, HttpCache.DEFAULT_MAX_ENTRY_BYTES)
        assertEquals(3600, HttpCache.DEFAULT_MAX_AGE)
    }

    // --- Authorization header tests ---

    @Test
    fun `authorization in request prevents serving from cache`() {
        val responseStream = buildHttpResponse(200, "secret data")
        val clientOutput = ByteArrayOutputStream()

        cache.cacheResponse(
            "example.com", "GET /api HTTP/1.1",
            responseStream, clientOutput, ByteArrayOutputStream(), ByteArrayInputStream(ByteArray(0)),
        )

        val serveOutput = ByteArrayOutputStream()
        val hit = cache.tryServeFromCache(
            "example.com", "GET /api HTTP/1.1",
            "Authorization: Bearer token123\r\n\r\n", serveOutput,
        )
        assertFalse(hit)
    }

    @Test
    fun `authorization in request prevents caching response`() {
        val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 6\r\n\r\nsecret"
        val responseStream = ByteArrayInputStream(response.toByteArray())
        val clientOutput = ByteArrayOutputStream()

        cache.cacheResponse(
            "example.com", "GET /api HTTP/1.1",
            responseStream, clientOutput, ByteArrayOutputStream(), ByteArrayInputStream(ByteArray(0)),
            requestHeaders = "Authorization: Basic dXNlcjpwYXNz\r\n\r\n",
        )

        val serveOutput = ByteArrayOutputStream()
        assertFalse(cache.tryServeFromCache("example.com", "GET /api HTTP/1.1", "", serveOutput))
    }

    // --- Pragma: no-cache tests ---

    @Test
    fun `pragma no-cache in request prevents serving from cache`() {
        val responseStream = buildHttpResponse(200, "cached content")
        val clientOutput = ByteArrayOutputStream()

        cache.cacheResponse(
            "example.com", "GET / HTTP/1.1",
            responseStream, clientOutput, ByteArrayOutputStream(), ByteArrayInputStream(ByteArray(0)),
        )

        val serveOutput = ByteArrayOutputStream()
        val hit = cache.tryServeFromCache(
            "example.com", "GET / HTTP/1.1",
            "Pragma: no-cache\r\n\r\n", serveOutput,
        )
        assertFalse(hit)
        assertEquals(1L, cache.misses)
    }

    @Test
    fun `pragma no-cache in response prevents caching`() {
        val response = "HTTP/1.1 200 OK\r\nPragma: no-cache\r\nContent-Type: text/html\r\nContent-Length: 4\r\n\r\ntest"
        val responseStream = ByteArrayInputStream(response.toByteArray())
        val clientOutput = ByteArrayOutputStream()

        cache.cacheResponse(
            "example.com", "GET / HTTP/1.1",
            responseStream, clientOutput, ByteArrayOutputStream(), ByteArrayInputStream(ByteArray(0)),
        )

        val serveOutput = ByteArrayOutputStream()
        assertFalse(cache.tryServeFromCache("example.com", "GET / HTTP/1.1", "", serveOutput))
    }

    // --- Cache-Control: no-cache tests ---

    @Test
    fun `cache-control no-cache prevents caching`() {
        val response = "HTTP/1.1 200 OK\r\nCache-Control: no-cache\r\nContent-Type: text/html\r\nContent-Length: 4\r\n\r\ntest"
        val responseStream = ByteArrayInputStream(response.toByteArray())
        val clientOutput = ByteArrayOutputStream()

        cache.cacheResponse(
            "example.com", "GET / HTTP/1.1",
            responseStream, clientOutput, ByteArrayOutputStream(), ByteArrayInputStream(ByteArray(0)),
        )

        val serveOutput = ByteArrayOutputStream()
        assertFalse(cache.tryServeFromCache("example.com", "GET / HTTP/1.1", "", serveOutput))
    }

    // --- Vary header tests ---

    @Test
    fun `vary header prevents caching`() {
        val response = "HTTP/1.1 200 OK\r\nVary: Accept-Encoding\r\nContent-Type: text/html\r\nContent-Length: 4\r\n\r\ntest"
        val responseStream = ByteArrayInputStream(response.toByteArray())
        val clientOutput = ByteArrayOutputStream()

        cache.cacheResponse(
            "example.com", "GET / HTTP/1.1",
            responseStream, clientOutput, ByteArrayOutputStream(), ByteArrayInputStream(ByteArray(0)),
        )

        val serveOutput = ByteArrayOutputStream()
        assertFalse(cache.tryServeFromCache("example.com", "GET / HTTP/1.1", "", serveOutput))
    }

    // --- max-age=0 tests ---

    @Test
    fun `max-age zero prevents caching`() {
        val response = "HTTP/1.1 200 OK\r\nCache-Control: max-age=0\r\nContent-Type: text/html\r\nContent-Length: 4\r\n\r\ntest"
        val responseStream = ByteArrayInputStream(response.toByteArray())
        val clientOutput = ByteArrayOutputStream()

        cache.cacheResponse(
            "example.com", "GET / HTTP/1.1",
            responseStream, clientOutput, ByteArrayOutputStream(), ByteArrayInputStream(ByteArray(0)),
        )

        val serveOutput = ByteArrayOutputStream()
        assertFalse(cache.tryServeFromCache("example.com", "GET / HTTP/1.1", "", serveOutput))
    }

    // --- Multiple Cache-Control directives ---

    @Test
    fun `multiple cache-control directives parsed correctly`() {
        val response = "HTTP/1.1 200 OK\r\nCache-Control: public, max-age=300, must-revalidate\r\nContent-Type: text/html\r\nContent-Length: 4\r\n\r\ntest"
        val responseStream = ByteArrayInputStream(response.toByteArray())
        val clientOutput = ByteArrayOutputStream()

        cache.cacheResponse(
            "example.com", "GET / HTTP/1.1",
            responseStream, clientOutput, ByteArrayOutputStream(), ByteArrayInputStream(ByteArray(0)),
        )

        // max-age=300 with public should be cached
        val serveOutput = ByteArrayOutputStream()
        assertTrue(cache.tryServeFromCache("example.com", "GET / HTTP/1.1", "", serveOutput))
    }

    @Test
    fun `cache-control with no-store among multiple directives prevents caching`() {
        val response = "HTTP/1.1 200 OK\r\nCache-Control: public, no-store, max-age=300\r\nContent-Type: text/html\r\nContent-Length: 4\r\n\r\ntest"
        val responseStream = ByteArrayInputStream(response.toByteArray())
        val clientOutput = ByteArrayOutputStream()

        cache.cacheResponse(
            "example.com", "GET / HTTP/1.1",
            responseStream, clientOutput, ByteArrayOutputStream(), ByteArrayInputStream(ByteArray(0)),
        )

        val serveOutput = ByteArrayOutputStream()
        assertFalse(cache.tryServeFromCache("example.com", "GET / HTTP/1.1", "", serveOutput))
    }

    // --- max-age with whitespace around = ---

    @Test
    fun `max-age with spaces around equals is parsed`() {
        val response = "HTTP/1.1 200 OK\r\nCache-Control: max-age = 600\r\nContent-Type: text/html\r\nContent-Length: 4\r\n\r\ntest"
        val responseStream = ByteArrayInputStream(response.toByteArray())
        val clientOutput = ByteArrayOutputStream()

        cache.cacheResponse(
            "example.com", "GET / HTTP/1.1",
            responseStream, clientOutput, ByteArrayOutputStream(), ByteArrayInputStream(ByteArray(0)),
        )

        val serveOutput = ByteArrayOutputStream()
        assertTrue(cache.tryServeFromCache("example.com", "GET / HTTP/1.1", "", serveOutput))
    }

    // --- Content-Encoding tests ---

    @Test
    fun `gzip content-encoding response is cached and served as-is`() {
        // Simulate a gzip-encoded response (body bytes are opaque to the cache)
        val gzipBody = byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00, 0x00, 0x00, 0x00, 0x00)
        val headerPart = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Encoding: gzip\r\nContent-Length: ${gzipBody.size}\r\n\r\n"
        val fullResponse = headerPart.toByteArray() + gzipBody
        val responseStream = ByteArrayInputStream(fullResponse)
        val clientOutput = ByteArrayOutputStream()

        cache.cacheResponse(
            "example.com", "GET /compressed HTTP/1.1",
            responseStream, clientOutput, ByteArrayOutputStream(), ByteArrayInputStream(ByteArray(0)),
        )

        val serveOutput = ByteArrayOutputStream()
        assertTrue(cache.tryServeFromCache("example.com", "GET /compressed HTTP/1.1", "", serveOutput))
        val served = serveOutput.toString()
        assertTrue(served.contains("Content-Encoding: gzip"))
    }

    // --- Response without Content-Length (e.g. chunked or connection-close) ---

    @Test
    fun `response without content-length is cached when small enough`() {
        val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\nHello chunked world"
        val responseStream = ByteArrayInputStream(response.toByteArray())
        val clientOutput = ByteArrayOutputStream()

        cache.cacheResponse(
            "example.com", "GET /nolen HTTP/1.1",
            responseStream, clientOutput, ByteArrayOutputStream(), ByteArrayInputStream(ByteArray(0)),
        )

        val serveOutput = ByteArrayOutputStream()
        assertTrue(cache.tryServeFromCache("example.com", "GET /nolen HTTP/1.1", "", serveOutput))
        assertTrue(serveOutput.toString().contains("Hello chunked world"))
    }

    // --- containsHeader and headerContains helper tests ---

    @Test
    fun `containsHeader is case-insensitive`() {
        val headers = "Host: example.com\r\nAuthorization: Bearer tok\r\n\r\n"
        assertTrue(HttpCache.containsHeader(headers, "authorization"))
        assertTrue(HttpCache.containsHeader(headers, "Authorization"))
        assertTrue(HttpCache.containsHeader(headers, "AUTHORIZATION"))
        assertTrue(HttpCache.containsHeader(headers, "Host"))
        assertFalse(HttpCache.containsHeader(headers, "X-Custom"))
    }

    @Test
    fun `headerContains checks value case-insensitively`() {
        val headers = "Pragma: no-cache\r\nHost: example.com\r\n\r\n"
        assertTrue(HttpCache.headerContains(headers, "Pragma", "no-cache"))
        assertTrue(HttpCache.headerContains(headers, "pragma", "NO-CACHE"))
        assertFalse(HttpCache.headerContains(headers, "Host", "no-cache"))
    }

    // --- Data is still forwarded to client even when not cached ---

    @Test
    fun `response is forwarded to client even when not cacheable`() {
        val response = "HTTP/1.1 200 OK\r\nCache-Control: no-store\r\nContent-Type: text/html\r\nContent-Length: 11\r\n\r\nhello world"
        val responseStream = ByteArrayInputStream(response.toByteArray())
        val clientOutput = ByteArrayOutputStream()

        cache.cacheResponse(
            "example.com", "GET / HTTP/1.1",
            responseStream, clientOutput, ByteArrayOutputStream(), ByteArrayInputStream(ByteArray(0)),
        )

        val forwarded = clientOutput.toString()
        assertTrue("Client should receive status line", forwarded.contains("200 OK"))
        assertTrue("Client should receive body", forwarded.contains("hello world"))
    }

    private fun buildHttpResponse(status: Int, body: String, contentLength: Int = body.length): ByteArrayInputStream {
        val response = "HTTP/1.1 $status OK\r\nContent-Type: text/html\r\nContent-Length: $contentLength\r\n\r\n$body"
        return ByteArrayInputStream(response.toByteArray())
    }
}
