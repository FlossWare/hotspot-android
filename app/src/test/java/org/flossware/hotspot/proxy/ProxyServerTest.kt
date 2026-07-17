package org.flossware.hotspot.proxy

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.SocketFactory

class ProxyServerTest {

    private lateinit var proxy: ProxyServer
    private var proxyPort = 0

    @Before
    fun setUp() {
        proxyPort = findFreePort()
        proxy = ProxyServer(
            bindAddress = InetAddress.getLoopbackAddress(),
            port = proxyPort,
            socketFactoryProvider = { SocketFactory.getDefault() },
        )
    }

    @After
    fun tearDown() {
        proxy.stop()
    }

    @Test
    fun `start sets running to true`() {
        proxy.start()
        Thread.sleep(200)
        assertTrue(proxy.isRunning)
    }

    @Test
    fun `stop sets running to false`() {
        proxy.start()
        Thread.sleep(200)
        proxy.stop()
        assertFalse(proxy.isRunning)
    }

    @Test
    fun `double start is idempotent`() {
        proxy.start()
        proxy.start()
        Thread.sleep(200)
        assertTrue(proxy.isRunning)
        proxy.stop()
    }

    @Test
    fun `bytesTransferred starts at zero`() {
        assertEquals(0L, proxy.bytesTransferred)
    }

    @Test
    fun `parseHostPort with port`() {
        val (host, port) = proxy.parseHostPort("example.com:443", 80)
        assertEquals("example.com", host)
        assertEquals(443, port)
    }

    @Test
    fun `parseHostPort without port uses default`() {
        val (host, port) = proxy.parseHostPort("example.com", 443)
        assertEquals("example.com", host)
        assertEquals(443, port)
    }

    @Test
    fun `parseHostPort with invalid port uses default`() {
        val (host, port) = proxy.parseHostPort("example.com:abc", 443)
        assertEquals("example.com", host)
        assertEquals(443, port)
    }

    @Test
    fun `parseHostPort with port 8080`() {
        val (host, port) = proxy.parseHostPort("localhost:8080", 80)
        assertEquals("localhost", host)
        assertEquals(8080, port)
    }

    @Test
    fun `readLine parses line with CRLF`() {
        val input = ByteArrayInputStream("Hello\r\nWorld\r\n".toByteArray())
        assertEquals("Hello", proxy.readLine(input))
        assertEquals("World", proxy.readLine(input))
    }

    @Test
    fun `readLine returns null on empty stream`() {
        val input = ByteArrayInputStream(ByteArray(0))
        assertNull(proxy.readLine(input))
    }

    @Test
    fun `readHeaders parses standard headers`() {
        val input = ByteArrayInputStream("Host: example.com\r\nContent-Type: text/html\r\nContent-Length: 42\r\n\r\n".toByteArray())
        val headers = proxy.readHeaders(input)
        assertEquals(3, headers.size)
        assertEquals("example.com", headers["Host"])
        assertEquals("text/html", headers["Content-Type"])
        assertEquals("42", headers["Content-Length"])
    }

    @Test
    fun `readHeaders handles empty headers`() {
        val input = ByteArrayInputStream("\r\n".toByteArray())
        val headers = proxy.readHeaders(input)
        assertTrue(headers.isEmpty())
    }

    @Test
    fun `readHeaders handles header with colon in value`() {
        val input = ByteArrayInputStream("Location: http://example.com:8080/path\r\n\r\n".toByteArray())
        val headers = proxy.readHeaders(input)
        assertEquals("http://example.com:8080/path", headers["Location"])
    }

    @Test
    fun `readHeaders skips malformed lines without colon`() {
        val input = ByteArrayInputStream("Valid: yes\r\nno-colon-here\r\nAlso-Valid: true\r\n\r\n".toByteArray())
        val headers = proxy.readHeaders(input)
        assertEquals(2, headers.size)
        assertEquals("yes", headers["Valid"])
        assertEquals("true", headers["Also-Valid"])
    }

    @Test
    fun `readHeaders trims whitespace`() {
        val input = ByteArrayInputStream("  Host  :  example.com  \r\n\r\n".toByteArray())
        val headers = proxy.readHeaders(input)
        assertEquals("example.com", headers["Host"])
    }

    @Test
    fun `relay copies data and counts bytes`() {
        val data = "Hello, World!".toByteArray()
        val input = ByteArrayInputStream(data)
        val output = ByteArrayOutputStream()
        proxy.relay(input, output)
        assertEquals("Hello, World!", output.toString())
        assertEquals(data.size.toLong(), proxy.bytesTransferred)
    }

    @Test
    fun `relay handles empty input`() {
        val input = ByteArrayInputStream(ByteArray(0))
        val output = ByteArrayOutputStream()
        proxy.relay(input, output)
        assertEquals(0, output.size())
        assertEquals(0L, proxy.bytesTransferred)
    }

    @Test
    fun `relay handles large data`() {
        val data = ByteArray(32768) { (it % 256).toByte() }
        val input = ByteArrayInputStream(data)
        val output = ByteArrayOutputStream()
        proxy.relay(input, output)
        assertEquals(data.size, output.size())
        assertEquals(data.size.toLong(), proxy.bytesTransferred)
        assertTrue(data.contentEquals(output.toByteArray()))
    }

    @Test
    fun `relayBytes copies exact number of bytes`() {
        val data = "Hello, World! Extra data here.".toByteArray()
        val input = ByteArrayInputStream(data)
        val output = ByteArrayOutputStream()
        proxy.relayBytes(input, output, 13)
        assertEquals("Hello, World!", output.toString())
        assertEquals(13L, proxy.bytesTransferred)
    }

    @Test
    fun `relayBytes handles zero length`() {
        val data = "data".toByteArray()
        val input = ByteArrayInputStream(data)
        val output = ByteArrayOutputStream()
        proxy.relayBytes(input, output, 0)
        assertEquals(0, output.size())
    }

    @Test
    fun `relayBytes handles input shorter than length`() {
        val data = "short".toByteArray()
        val input = ByteArrayInputStream(data)
        val output = ByteArrayOutputStream()
        proxy.relayBytes(input, output, 100)
        assertEquals("short", output.toString())
        assertEquals(5L, proxy.bytesTransferred)
    }

    @Test
    fun `sendError writes proper HTTP response`() {
        proxy.start()
        Thread.sleep(300)

        val client = Socket(InetAddress.getLoopbackAddress(), proxyPort)
        client.getOutputStream().write("BAD\r\n".toByteArray())
        client.getOutputStream().flush()

        val response = client.getInputStream().bufferedReader().readLine()
        assertTrue(response?.contains("400") == true)
        client.close()
    }

    @Test
    fun `HTTP proxy forwards GET request`() {
        val echoServer = createEchoHttpServer()
        val echoPort = echoServer.localPort

        proxy.start()
        Thread.sleep(300)

        val client = Socket(InetAddress.getLoopbackAddress(), proxyPort)
        val request = "GET http://127.0.0.1:$echoPort/test?q=1 HTTP/1.1\r\n" +
            "Host: 127.0.0.1:$echoPort\r\n" +
            "Proxy-Connection: keep-alive\r\n" +
            "\r\n"
        client.getOutputStream().write(request.toByteArray())
        client.getOutputStream().flush()

        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        val statusLine = reader.readLine()
        assertTrue("Expected 200, got: $statusLine", statusLine?.contains("200") == true)

        client.close()
        echoServer.close()
    }

    @Test
    fun `CONNECT tunnel establishes connection`() {
        val echoServer = createEchoHttpServer()
        val echoPort = echoServer.localPort

        proxy.start()
        Thread.sleep(500)

        val client = Socket(InetAddress.getLoopbackAddress(), proxyPort)
        client.soTimeout = 5000
        val connectRequest = "CONNECT 127.0.0.1:$echoPort HTTP/1.1\r\n" +
            "Host: 127.0.0.1:$echoPort\r\n" +
            "\r\n"
        client.getOutputStream().write(connectRequest.toByteArray())
        client.getOutputStream().flush()

        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        val statusLine = reader.readLine()
        assertTrue("Expected 200, got: $statusLine", statusLine?.contains("200") == true)

        Thread.sleep(100)
        client.getOutputStream().write("GET / HTTP/1.1\r\nHost: test\r\n\r\n".toByteArray())
        client.getOutputStream().flush()

        val tunnelResponse = reader.readLine()
        assertTrue("Expected echo response, got: $tunnelResponse", tunnelResponse?.contains("200") == true)

        client.close()
        echoServer.close()
    }

    @Test
    fun `proxy returns 502 when no socket factory available`() {
        val noNetPort = findFreePort()
        val noNetProxy = ProxyServer(
            bindAddress = InetAddress.getLoopbackAddress(),
            port = noNetPort,
            socketFactoryProvider = { null },
        )
        noNetProxy.start()
        Thread.sleep(300)

        val client = Socket(InetAddress.getLoopbackAddress(), noNetPort)
        val request = "GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\n\r\n"
        client.getOutputStream().write(request.toByteArray())
        client.getOutputStream().flush()

        val response = client.getInputStream().bufferedReader().readLine()
        assertTrue("Expected 502, got: $response", response?.contains("502") == true)

        client.close()
        noNetProxy.stop()
    }

    @Test
    fun `proxy returns 400 for invalid URL`() {
        proxy.start()
        Thread.sleep(300)

        val client = Socket(InetAddress.getLoopbackAddress(), proxyPort)
        val request = "GET not-a-url HTTP/1.1\r\nHost: x\r\n\r\n"
        client.getOutputStream().write(request.toByteArray())
        client.getOutputStream().flush()

        val response = client.getInputStream().bufferedReader().readLine()
        assertTrue("Expected 400, got: $response", response?.contains("400") == true)

        client.close()
    }

    @Test
    fun `proxy strips Proxy-Connection header`() {
        val echoServer = createHeaderEchoServer()
        val echoPort = echoServer.localPort

        proxy.start()
        Thread.sleep(300)

        val client = Socket(InetAddress.getLoopbackAddress(), proxyPort)
        val request = "GET http://127.0.0.1:$echoPort/ HTTP/1.1\r\n" +
            "Host: 127.0.0.1:$echoPort\r\n" +
            "Proxy-Connection: keep-alive\r\n" +
            "X-Custom: test\r\n" +
            "\r\n"
        client.getOutputStream().write(request.toByteArray())
        client.getOutputStream().flush()

        val fullResponse = client.getInputStream().bufferedReader().readText()
        assertFalse("Should strip Proxy-Connection", fullResponse.contains("Proxy-Connection"))
        assertTrue("Should keep X-Custom", fullResponse.contains("X-Custom"))

        client.close()
        echoServer.close()
    }

    @Test
    fun `proxy adds Host header when missing`() {
        val echoServer = createHeaderEchoServer()
        val echoPort = echoServer.localPort

        proxy.start()
        Thread.sleep(300)

        val client = Socket(InetAddress.getLoopbackAddress(), proxyPort)
        val request = "GET http://127.0.0.1:$echoPort/ HTTP/1.1\r\n\r\n"
        client.getOutputStream().write(request.toByteArray())
        client.getOutputStream().flush()

        val fullResponse = client.getInputStream().bufferedReader().readText()
        assertTrue("Should add Host header", fullResponse.contains("Host:"))

        client.close()
        echoServer.close()
    }

    private fun findFreePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }

    private fun createEchoHttpServer(): ServerSocket {
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        Thread {
            while (!server.isClosed) {
                try {
                    val client = server.accept()
                    Thread {
                        try {
                            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                            }
                            val body = "OK"
                            val response = "HTTP/1.1 200 OK\r\n" +
                                "Content-Length: ${body.length}\r\n" +
                                "Connection: close\r\n" +
                                "\r\n" + body
                            client.getOutputStream().write(response.toByteArray())
                            client.getOutputStream().flush()
                            client.close()
                        } catch (_: Exception) {
                        }
                    }.start()
                } catch (_: Exception) {
                    break
                }
            }
        }.apply { isDaemon = true; start() }
        return server
    }

    private fun createHeaderEchoServer(): ServerSocket {
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        Thread {
            while (!server.isClosed) {
                try {
                    val client = server.accept()
                    Thread {
                        try {
                            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                            val headers = StringBuilder()
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                headers.appendLine(line)
                            }
                            val body = headers.toString()
                            val response = "HTTP/1.1 200 OK\r\n" +
                                "Content-Length: ${body.length}\r\n" +
                                "Connection: close\r\n" +
                                "\r\n" + body
                            client.getOutputStream().write(response.toByteArray())
                            client.getOutputStream().flush()
                            client.close()
                        } catch (_: Exception) {
                        }
                    }.start()
                } catch (_: Exception) {
                    break
                }
            }
        }.apply { isDaemon = true; start() }
        return server
    }
}
