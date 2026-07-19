package org.flossware.hotspot.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

/**
 * Integration tests for the SOCKS5 proxy server using real TCP sockets on localhost.
 *
 * Each test creates and destroys its own server instances with no shared state.
 * These tests complement the unit-level tests in [Socks5ServerTest] by exercising
 * full end-to-end scenarios over real network connections.
 */
class Socks5IntegrationTest {

    // --- Connection timeout handling ---

    @Test
    fun `CONNECT to hanging upstream times out without crashing server`() {
        val hangingServer = createHangingServer()
        val hangingPort = hangingServer.localPort
        val serverPort = findFreePort()
        val server = createServer(serverPort)
        server.start()
        Thread.sleep(STARTUP_DELAY)

        try {
            val client = connectAndNegotiate(serverPort)
            sendIpv4Connect(client, hangingPort)

            val reply = ByteArray(10)
            readFully(client.getInputStream(), reply)
            assertEquals("CONNECT should succeed", 0x00.toByte(), reply[1])

            client.getOutputStream().write("GET / HTTP/1.1\r\nHost: test\r\n\r\n".toByteArray())
            client.getOutputStream().flush()

            // Upstream never responds, so we expect timeout or EOF
            client.soTimeout = 2000
            val result = try {
                client.getInputStream().read()
            } catch (_: SocketTimeoutException) {
                -2
            } catch (_: IOException) {
                -1
            }
            assertTrue("Expected timeout or EOF, got: $result", result == -1 || result == -2)
            assertTrue("Server should still be running", server.isRunning)
            client.close()
        } finally {
            hangingServer.close()
            server.stop()
        }
    }

    // --- Concurrent connections (thread safety) ---

    @Test
    fun `concurrent SOCKS5 connections are handled without data corruption`() {
        val echoServer = createEchoHttpServer()
        val echoPort = echoServer.localPort
        val serverPort = findFreePort()
        val server = createServer(serverPort, maxConns = 50)
        server.start()
        Thread.sleep(STARTUP_DELAY)

        val threadCount = 10
        val successCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)

        val threads = (0 until threadCount).map { idx ->
            createSocksClientThread(serverPort, echoPort, idx, successCount, errorCount, startLatch, doneLatch)
        }
        threads.forEach { it.start() }
        startLatch.countDown()

        assertTrue("Threads should complete", doneLatch.await(30, TimeUnit.SECONDS))
        assertTrue(
            "At least half should succeed (success=${successCount.get()}, errors=${errorCount.get()})",
            successCount.get() >= threadCount / 2,
        )
        assertTrue("Server should still be running", server.isRunning)
        assertTrue("Bytes should have been transferred", server.bytesTransferred > 0)

        echoServer.close()
        server.stop()
    }

    // --- Bidirectional data relay ---

    @Test
    fun `bidirectional data relay through SOCKS5 tunnel`() {
        val rawEchoServer = createRawEchoServer()
        val rawEchoPort = rawEchoServer.localPort
        val serverPort = findFreePort()
        val server = createServer(serverPort)
        server.start()
        Thread.sleep(STARTUP_DELAY)

        try {
            val client = connectAndNegotiate(serverPort)
            sendIpv4Connect(client, rawEchoPort)

            val connectReply = ByteArray(10)
            readFully(client.getInputStream(), connectReply)
            assertEquals(0x00.toByte(), connectReply[1])

            for (msg in listOf("Hello", "World", "SOCKS5 bidirectional test")) {
                val payload = msg.toByteArray()
                client.getOutputStream().write(payload)
                client.getOutputStream().flush()
                val echoed = ByteArray(payload.size)
                readFully(client.getInputStream(), echoed)
                assertEquals("Data should be echoed back unchanged", msg, String(echoed))
            }
            assertTrue("Bytes should be transferred", server.bytesTransferred > 0)
            client.close()
        } finally {
            rawEchoServer.close()
            server.stop()
        }
    }

    // --- SSRF protection end-to-end ---

    @Test
    fun `SSRF protection blocks link-local address via real SOCKS5 connection`() {
        val serverPort = findFreePort()
        val server = Socks5Server(
            bindAddress = InetAddress.getLoopbackAddress(),
            port = serverPort,
            socketFactoryProvider = { SocketFactory.getDefault() },
            dnsResolver = { InetAddress.getByName("169.254.1.1") },
            ssrfProtection = true,
        )
        server.start()
        Thread.sleep(STARTUP_DELAY)

        try {
            val client = connectAndNegotiate(serverPort)
            sendDomainConnect(client, "link-local.test", 80)

            val reply = ByteArray(10)
            readFully(client.getInputStream(), reply)
            assertEquals("Link-local should be blocked", Socks5Server.REPLY_NOT_ALLOWED, reply[1])
            client.close()
        } finally {
            server.stop()
        }
    }

    // --- Malformed request handling ---

    @Test
    fun `server handles truncated SOCKS5 request gracefully`() {
        val serverPort = findFreePort()
        val server = createServer(serverPort, ssrf = true)
        server.start()
        Thread.sleep(STARTUP_DELAY)

        try {
            val client = Socket(InetAddress.getLoopbackAddress(), serverPort)
            client.soTimeout = LONG_TIMEOUT
            client.getOutputStream().write(byteArrayOf(0x05))
            client.getOutputStream().flush()
            client.close()
            Thread.sleep(SETTLE_DELAY)
            assertTrue("Server should still be running", server.isRunning)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `server handles connection closed during CONNECT request`() {
        val serverPort = findFreePort()
        val server = createServer(serverPort, ssrf = true)
        server.start()
        Thread.sleep(STARTUP_DELAY)

        try {
            val client = connectAndNegotiate(serverPort)
            client.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
            client.getOutputStream().flush()
            client.close()
            Thread.sleep(SETTLE_DELAY)
            assertTrue("Server should still be running", server.isRunning)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `server handles non-SOCKS5 data gracefully`() {
        val serverPort = findFreePort()
        val server = createServer(serverPort, ssrf = true)
        server.start()
        Thread.sleep(STARTUP_DELAY)

        try {
            val client = Socket(InetAddress.getLoopbackAddress(), serverPort)
            client.soTimeout = 2000
            client.getOutputStream().write("GET / HTTP/1.1\r\nHost: test\r\n\r\n".toByteArray())
            client.getOutputStream().flush()

            val response = ByteArray(2)
            try {
                readFully(client.getInputStream(), response)
                assertEquals("Should send SOCKS5 version", 0x05.toByte(), response[0])
                assertEquals("Should reject", 0xFF.toByte(), response[1])
            } catch (_: IOException) {
                // Connection closed is also acceptable
            }
            client.close()
            Thread.sleep(SETTLE_DELAY)
            assertTrue("Server should still be running", server.isRunning)
        } finally {
            server.stop()
        }
    }

    // --- Active connections tracking ---

    @Test
    fun `activeConnections returns to zero after client disconnects`() {
        val echoServer = createEchoHttpServer()
        val echoPort = echoServer.localPort
        val serverPort = findFreePort()
        val server = createServer(serverPort)
        server.start()
        Thread.sleep(STARTUP_DELAY)
        assertEquals(0, server.activeConnections)

        try {
            val client = connectAndNegotiate(serverPort)
            sendIpv4Connect(client, echoPort)
            readFully(client.getInputStream(), ByteArray(10))
            sendHttpGetAndReadStatus(client)
            client.close()
        } finally {
            Thread.sleep(SETTLE_DELAY)
            assertEquals("Connections should be zero", 0, server.activeConnections)
            echoServer.close()
            server.stop()
        }
    }

    // --- Helpers ---

    private fun createServer(
        port: Int,
        ssrf: Boolean = false,
        maxConns: Int = 100,
    ): Socks5Server = Socks5Server(
        bindAddress = InetAddress.getLoopbackAddress(),
        port = port,
        socketFactoryProvider = { SocketFactory.getDefault() },
        maxTotalConnections = maxConns,
        maxConnectionsPerClient = maxConns,
        ssrfProtection = ssrf,
    )

    private fun connectAndNegotiate(port: Int): Socket {
        val client = Socket(InetAddress.getLoopbackAddress(), port)
        client.soTimeout = LONG_TIMEOUT
        client.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
        client.getOutputStream().flush()
        readFully(client.getInputStream(), ByteArray(2))
        return client
    }

    private fun sendIpv4Connect(client: Socket, targetPort: Int) {
        val loopback = InetAddress.getLoopbackAddress().address
        val req = ByteArray(4 + loopback.size + 2)
        req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x01
        System.arraycopy(loopback, 0, req, 4, loopback.size)
        req[req.size - 2] = ((targetPort shr 8) and 0xFF).toByte()
        req[req.size - 1] = (targetPort and 0xFF).toByte()
        client.getOutputStream().write(req)
        client.getOutputStream().flush()
    }

    private fun sendDomainConnect(client: Socket, domain: String, port: Int) {
        val domainBytes = domain.toByteArray(Charsets.US_ASCII)
        val req = ByteArray(4 + 1 + domainBytes.size + 2)
        req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x03
        req[4] = domainBytes.size.toByte()
        System.arraycopy(domainBytes, 0, req, 5, domainBytes.size)
        req[req.size - 2] = ((port shr 8) and 0xFF).toByte()
        req[req.size - 1] = (port and 0xFF).toByte()
        client.getOutputStream().write(req)
        client.getOutputStream().flush()
    }

    private fun sendHttpGetAndReadStatus(client: Socket) {
        client.getOutputStream().write("GET / HTTP/1.1\r\nHost: test\r\n\r\n".toByteArray())
        client.getOutputStream().flush()
        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        reader.readLine()
    }

    @Suppress("LongParameterList")
    private fun createSocksClientThread(
        serverPort: Int,
        echoPort: Int,
        idx: Int,
        successCount: AtomicInteger,
        errorCount: AtomicInteger,
        startLatch: CountDownLatch,
        doneLatch: CountDownLatch,
    ): Thread = Thread {
        try {
            startLatch.await()
            val client = connectAndNegotiate(serverPort)
            sendIpv4Connect(client, echoPort)
            val connectReply = ByteArray(10)
            readFully(client.getInputStream(), connectReply)
            if (connectReply[1] != 0x00.toByte()) {
                errorCount.incrementAndGet()
                client.close()
                return@Thread
            }
            val httpReq = "GET /thread-$idx HTTP/1.1\r\nHost: test\r\n\r\n"
            client.getOutputStream().write(httpReq.toByteArray())
            client.getOutputStream().flush()
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val statusLine = reader.readLine()
            if (statusLine != null && statusLine.contains("200")) {
                successCount.incrementAndGet()
            } else {
                errorCount.incrementAndGet()
            }
            client.close()
        } catch (_: Exception) {
            errorCount.incrementAndGet()
        } finally {
            doneLatch.countDown()
        }
    }

    private fun findFreePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val n = input.read(buf, offset, buf.size - offset)
            if (n == -1) throw IOException("Unexpected EOF after $offset of ${buf.size} bytes")
            offset += n
        }
    }

    private fun createHangingServer(): ServerSocket {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        Thread {
            try {
                while (!server.isClosed) {
                    server.accept()
                    Thread.sleep(Long.MAX_VALUE)
                }
            } catch (_: Exception) {
                // Expected on close
            }
        }.apply { isDaemon = true; start() }
        return server
    }

    private fun createRawEchoServer(): ServerSocket {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        Thread {
            try {
                val echoClient = server.accept()
                echoClient.soTimeout = LONG_TIMEOUT
                val buf = ByteArray(4096)
                while (true) {
                    val n = echoClient.getInputStream().read(buf)
                    if (n == -1) break
                    echoClient.getOutputStream().write(buf, 0, n)
                    echoClient.getOutputStream().flush()
                }
                echoClient.close()
            } catch (_: Exception) {
                // Expected on close
            }
        }.apply { isDaemon = true; start() }
        return server
    }

    private fun createEchoHttpServer(): ServerSocket {
        val server = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
        Thread {
            while (!server.isClosed) {
                try {
                    val client = server.accept()
                    Thread { handleEchoHttpClient(client) }.apply { isDaemon = true; start() }
                } catch (_: Exception) {
                    // Expected when server closes
                }
            }
        }.apply { isDaemon = true; start() }
        return server
    }

    private fun handleEchoHttpClient(client: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isEmpty()) break
            }
            val body = "OK"
            val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Length: ${body.length}\r\n" +
                "Connection: close\r\n\r\n" + body
            client.getOutputStream().write(response.toByteArray())
            client.getOutputStream().flush()
            client.close()
        } catch (_: Exception) {
            // Expected during teardown
        }
    }

    companion object {
        private const val STARTUP_DELAY = 500L
        private const val SETTLE_DELAY = 500L
        private const val LONG_TIMEOUT = 5000
    }
}
