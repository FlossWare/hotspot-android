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

    private fun buildHttpResponse(status: Int, body: String, contentLength: Int = body.length): ByteArrayInputStream {
        val response = "HTTP/1.1 $status OK\r\nContent-Type: text/html\r\nContent-Length: $contentLength\r\n\r\n$body"
        return ByteArrayInputStream(response.toByteArray())
    }
}
