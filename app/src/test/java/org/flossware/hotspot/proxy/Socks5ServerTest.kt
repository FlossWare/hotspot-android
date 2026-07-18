package org.flossware.hotspot.proxy

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

class Socks5ServerTest {

    private lateinit var server: Socks5Server
    private var serverPort = 0

    @Before
    fun setUp() {
        serverPort = findFreePort()
        server = Socks5Server(
            bindAddress = InetAddress.getLoopbackAddress(),
            port = serverPort,
            socketFactoryProvider = { SocketFactory.getDefault() },
            ssrfProtection = false,
        )
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `start sets running to true`() {
        server.start()
        Thread.sleep(200)
        assertTrue(server.isRunning)
    }

    @Test
    fun `stop sets running to false`() {
        server.start()
        Thread.sleep(200)
        server.stop()
        assertFalse(server.isRunning)
    }

    @Test
    fun `double start is idempotent`() {
        server.start()
        server.start()
        Thread.sleep(200)
        assertTrue(server.isRunning)
        server.stop()
    }

    @Test
    fun `bytesTransferred starts at zero`() {
        assertEquals(0L, server.bytesTransferred)
    }

    @Test
    fun `activeConnections starts at zero`() {
        assertEquals(0, server.activeConnections)
    }

    // --- Negotiation tests (no-auth mode) ---

    @Test
    fun `negotiate accepts NO_AUTH method`() {
        val input = ByteArrayInputStream(byteArrayOf(0x05, 0x01, 0x00))
        val output = ByteArrayOutputStream()
        assertTrue(server.negotiate(input, output))
        val response = output.toByteArray()
        assertEquals(2, response.size)
        assertEquals(0x05.toByte(), response[0])
        assertEquals(0x00.toByte(), response[1])
    }

    @Test
    fun `negotiate rejects wrong version`() {
        val input = ByteArrayInputStream(byteArrayOf(0x04, 0x01, 0x00))
        val output = ByteArrayOutputStream()
        assertFalse(server.negotiate(input, output))
        val response = output.toByteArray()
        assertEquals(0x05.toByte(), response[0])
        assertEquals(0xFF.toByte(), response[1])
    }

    @Test
    fun `negotiate rejects when NO_AUTH not offered`() {
        val input = ByteArrayInputStream(byteArrayOf(0x05, 0x01, 0x02))
        val output = ByteArrayOutputStream()
        assertFalse(server.negotiate(input, output))
        val response = output.toByteArray()
        assertEquals(0xFF.toByte(), response[1])
    }

    @Test
    fun `negotiate accepts NO_AUTH among multiple methods`() {
        val input = ByteArrayInputStream(byteArrayOf(0x05, 0x03, 0x01, 0x00, 0x02))
        val output = ByteArrayOutputStream()
        assertTrue(server.negotiate(input, output))
        assertEquals(0x00.toByte(), output.toByteArray()[1])
    }

    @Test
    fun `negotiate rejects empty stream`() {
        val input = ByteArrayInputStream(ByteArray(0))
        val output = ByteArrayOutputStream()
        assertFalse(server.negotiate(input, output))
    }

    // --- Auth tests (RFC 1929) ---

    @Test
    fun `negotiate requires auth when credentials configured`() {
        val authServer = Socks5Server(
            bindAddress = InetAddress.getLoopbackAddress(),
            port = findFreePort(),
            socketFactoryProvider = { SocketFactory.getDefault() },
            username = "user",
            password = "pass",
        )

        // Offer only NO_AUTH — should be rejected
        val input = ByteArrayInputStream(byteArrayOf(0x05, 0x01, 0x00))
        val output = ByteArrayOutputStream()
        assertFalse(authServer.negotiate(input, output))
        assertEquals(0xFF.toByte(), output.toByteArray()[1])
    }

    @Test
    fun `negotiate selects username password auth when configured`() {
        val authServer = Socks5Server(
            bindAddress = InetAddress.getLoopbackAddress(),
            port = findFreePort(),
            socketFactoryProvider = { SocketFactory.getDefault() },
            username = "user",
            password = "pass",
        )

        // Offer AUTH_USERNAME_PASSWORD (0x02) + valid subnegotiation
        val user = "user".toByteArray(Charsets.UTF_8)
        val pass = "pass".toByteArray(Charsets.UTF_8)
        val authData = byteArrayOf(
            0x05, 0x02, 0x00, 0x02, // negotiation: version, 2 methods, NO_AUTH + USERNAME_PASSWORD
            0x01, // auth version
            user.size.toByte(), *user,
            pass.size.toByte(), *pass,
        )
        val input = ByteArrayInputStream(authData)
        val output = ByteArrayOutputStream()
        assertTrue(authServer.negotiate(input, output))

        val response = output.toByteArray()
        // Should select method 0x02
        assertEquals(0x05.toByte(), response[0])
        assertEquals(0x02.toByte(), response[1])
        // Auth subnegotiation: version 0x01, status 0x00 (success)
        assertEquals(0x01.toByte(), response[2])
        assertEquals(0x00.toByte(), response[3])
    }

    @Test
    fun `auth rejects wrong password`() {
        val authServer = Socks5Server(
            bindAddress = InetAddress.getLoopbackAddress(),
            port = findFreePort(),
            socketFactoryProvider = { SocketFactory.getDefault() },
            username = "user",
            password = "correct",
        )

        val user = "user".toByteArray(Charsets.UTF_8)
        val pass = "wrong".toByteArray(Charsets.UTF_8)
        val authData = byteArrayOf(
            0x05, 0x01, 0x02, // offer only USERNAME_PASSWORD
            0x01,
            user.size.toByte(), *user,
            pass.size.toByte(), *pass,
        )
        val input = ByteArrayInputStream(authData)
        val output = ByteArrayOutputStream()
        assertFalse(authServer.negotiate(input, output))

        val response = output.toByteArray()
        assertEquals(0x02.toByte(), response[1]) // selected method
        assertEquals(0x01.toByte(), response[2]) // auth version
        assertEquals(0x01.toByte(), response[3]) // failure status
    }

    @Test
    fun `auth rejects wrong username`() {
        val authServer = Socks5Server(
            bindAddress = InetAddress.getLoopbackAddress(),
            port = findFreePort(),
            socketFactoryProvider = { SocketFactory.getDefault() },
            username = "admin",
            password = "pass",
        )

        val user = "user".toByteArray(Charsets.UTF_8)
        val pass = "pass".toByteArray(Charsets.UTF_8)
        val authData = byteArrayOf(
            0x05, 0x01, 0x02,
            0x01,
            user.size.toByte(), *user,
            pass.size.toByte(), *pass,
        )
        val input = ByteArrayInputStream(authData)
        val output = ByteArrayOutputStream()
        assertFalse(authServer.negotiate(input, output))
    }

    @Test
    fun `authenticateUsernamePassword succeeds with correct credentials`() {
        val authServer = Socks5Server(
            bindAddress = InetAddress.getLoopbackAddress(),
            port = findFreePort(),
            socketFactoryProvider = { SocketFactory.getDefault() },
            username = "testuser",
            password = "testpass",
        )

        val user = "testuser".toByteArray(Charsets.UTF_8)
        val pass = "testpass".toByteArray(Charsets.UTF_8)
        val data = byteArrayOf(
            0x01, // auth version
            user.size.toByte(), *user,
            pass.size.toByte(), *pass,
        )
        val input = ByteArrayInputStream(data)
        val output = ByteArrayOutputStream()
        assertTrue(authServer.authenticateUsernamePassword(input, output))
        val response = output.toByteArray()
        assertEquals(0x01.toByte(), response[0]) // version
        assertEquals(0x00.toByte(), response[1]) // success
    }

    @Test
    fun `authenticateUsernamePassword rejects wrong auth version`() {
        val authServer = Socks5Server(
            bindAddress = InetAddress.getLoopbackAddress(),
            port = findFreePort(),
            socketFactoryProvider = { SocketFactory.getDefault() },
            username = "user",
            password = "pass",
        )

        val data = byteArrayOf(0x02, 0x04, *"user".toByteArray(), 0x04, *"pass".toByteArray())
        val input = ByteArrayInputStream(data)
        val output = ByteArrayOutputStream()
        assertFalse(authServer.authenticateUsernamePassword(input, output))
    }

    // --- Address parsing tests ---

    @Test
    fun `readAddress parses IPv4`() {
        val data = byteArrayOf(
            0x01,                                   // IPv4
            127.toByte(), 0, 0, 1,                  // 127.0.0.1
            0x04, 0x38.toByte(),                     // port 1080
        )
        val (host, port) = server.readAddress(ByteArrayInputStream(data))
        assertEquals("127.0.0.1", host)
        assertEquals(1080, port)
    }

    @Test
    fun `readAddress parses domain name`() {
        val domain = "example.com".toByteArray(Charsets.US_ASCII)
        val data = ByteArray(1 + 1 + domain.size + 2)
        data[0] = 0x03 // domain
        data[1] = domain.size.toByte()
        System.arraycopy(domain, 0, data, 2, domain.size)
        data[data.size - 2] = 0x00 // port 80 high byte
        data[data.size - 1] = 0x50 // port 80 low byte
        val (host, port) = server.readAddress(ByteArrayInputStream(data))
        assertEquals("example.com", host)
        assertEquals(80, port)
    }

    @Test
    fun `readAddress parses IPv6`() {
        val addr = ByteArray(16)
        addr[15] = 1 // ::1
        val data = ByteArray(1 + 16 + 2)
        data[0] = 0x04 // IPv6
        System.arraycopy(addr, 0, data, 1, 16)
        data[data.size - 2] = 0x01 // port 443 high
        data[data.size - 1] = 0xBB.toByte() // port 443 low
        val (host, port) = server.readAddress(ByteArrayInputStream(data))
        assertTrue("Expected IPv6 loopback, got: $host", host.contains("1") || host.contains("::1"))
        assertEquals(443, port)
    }

    @Test(expected = java.io.IOException::class)
    fun `readAddress throws on empty stream`() {
        server.readAddress(ByteArrayInputStream(ByteArray(0)))
    }

    // --- Reply tests ---

    @Test
    fun `sendReply writes correct success response`() {
        val output = ByteArrayOutputStream()
        val addr = InetAddress.getByName("127.0.0.1")
        server.sendReply(output, Socks5Server.REPLY_SUCCESS, addr, 8080)
        val reply = output.toByteArray()
        assertEquals(10, reply.size) // 4 header + 4 IPv4 + 2 port
        assertEquals(0x05.toByte(), reply[0]) // version
        assertEquals(0x00.toByte(), reply[1]) // success
        assertEquals(0x00.toByte(), reply[2]) // reserved
        assertEquals(0x01.toByte(), reply[3]) // IPv4
        assertEquals(0x1F.toByte(), reply[8]) // port 8080 >> 8
        assertEquals(0x90.toByte(), reply[9]) // port 8080 & 0xFF
    }

    @Test
    fun `sendReply writes failure with zero address`() {
        val output = ByteArrayOutputStream()
        server.sendReply(output, Socks5Server.REPLY_GENERAL_FAILURE)
        val reply = output.toByteArray()
        assertEquals(10, reply.size)
        assertEquals(0x01.toByte(), reply[1]) // general failure
        assertEquals(0x01.toByte(), reply[3]) // IPv4
    }

    @Test
    fun `sendReply writes CMD_NOT_SUPPORTED`() {
        val output = ByteArrayOutputStream()
        server.sendReply(output, Socks5Server.REPLY_CMD_NOT_SUPPORTED)
        val reply = output.toByteArray()
        assertEquals(0x07.toByte(), reply[1])
    }

    @Test
    fun `sendReply writes IPv6 address correctly`() {
        val output = ByteArrayOutputStream()
        val addr = InetAddress.getByName("::1")
        server.sendReply(output, Socks5Server.REPLY_SUCCESS, addr, 443)
        val reply = output.toByteArray()
        assertEquals(22, reply.size) // 4 header + 16 IPv6 + 2 port
        assertEquals(0x04.toByte(), reply[3]) // IPv6
    }

    // --- Relay tests ---

    @Test
    fun `relay copies data and counts bytes`() {
        val data = "Hello, World!".toByteArray()
        val input = ByteArrayInputStream(data)
        val output = ByteArrayOutputStream()
        server.relay(input, output)
        assertEquals("Hello, World!", output.toString())
        assertEquals(data.size.toLong(), server.bytesTransferred)
    }

    @Test
    fun `relay handles empty input`() {
        val input = ByteArrayInputStream(ByteArray(0))
        val output = ByteArrayOutputStream()
        server.relay(input, output)
        assertEquals(0, output.size())
        assertEquals(0L, server.bytesTransferred)
    }

    @Test
    fun `relay handles large data`() {
        val data = ByteArray(32768) { (it % 256).toByte() }
        val input = ByteArrayInputStream(data)
        val output = ByteArrayOutputStream()
        server.relay(input, output)
        assertEquals(data.size, output.size())
        assertEquals(data.size.toLong(), server.bytesTransferred)
        assertTrue(data.contentEquals(output.toByteArray()))
    }

    // --- Constant-time comparison ---

    @Test
    fun `constantTimeEquals returns true for equal strings`() {
        assertTrue(Socks5Server.constantTimeEquals("hello", "hello"))
    }

    @Test
    fun `constantTimeEquals returns false for different strings`() {
        assertFalse(Socks5Server.constantTimeEquals("hello", "world"))
    }

    @Test
    fun `constantTimeEquals returns false for different lengths`() {
        assertFalse(Socks5Server.constantTimeEquals("short", "longer"))
    }

    @Test
    fun `constantTimeEquals handles empty strings`() {
        assertTrue(Socks5Server.constantTimeEquals("", ""))
    }

    // --- Connection limiting tests ---

    @Test
    fun `server rejects connections over per-client limit`() {
        val limitPort = findFreePort()
        val limitServer = Socks5Server(
            bindAddress = InetAddress.getLoopbackAddress(),
            port = limitPort,
            socketFactoryProvider = { SocketFactory.getDefault() },
            maxConnectionsPerClient = 2,
            maxTotalConnections = 100,
            ssrfProtection = false,
        )
        limitServer.start()
        Thread.sleep(300)

        val echoServer = createEchoHttpServer()
        val echoPort = echoServer.localPort
        val sockets = mutableListOf<Socket>()

        try {
            // Open 2 connections that stay alive (held open as long tunnels)
            for (i in 0 until 2) {
                val client = Socket(InetAddress.getLoopbackAddress(), limitPort)
                client.soTimeout = 2000
                sockets.add(client)

                // Negotiate
                client.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
                client.getOutputStream().flush()
                readFully(client.getInputStream(), ByteArray(2))

                // CONNECT to echo server
                val loopback = InetAddress.getLoopbackAddress().address
                val req = ByteArray(4 + loopback.size + 2)
                req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x01
                System.arraycopy(loopback, 0, req, 4, loopback.size)
                req[req.size - 2] = ((echoPort shr 8) and 0xFF).toByte()
                req[req.size - 1] = (echoPort and 0xFF).toByte()
                client.getOutputStream().write(req)
                client.getOutputStream().flush()
                readFully(client.getInputStream(), ByteArray(10))
            }

            // Third connection should be immediately closed by server
            Thread.sleep(100)
            val blocked = Socket(InetAddress.getLoopbackAddress(), limitPort)
            blocked.soTimeout = 1000

            // The server closes the socket immediately on limit exceeded,
            // so reading should get -1 or an exception
            val result = try {
                blocked.getInputStream().read()
            } catch (_: Exception) {
                -1
            }
            assertEquals("Third connection should be rejected", -1, result)
            blocked.close()
        } finally {
            sockets.forEach { it.close() }
            echoServer.close()
            limitServer.stop()
        }
    }

    @Test
    fun `server rejects connections over total limit`() {
        val limitPort = findFreePort()
        val limitServer = Socks5Server(
            bindAddress = InetAddress.getLoopbackAddress(),
            port = limitPort,
            socketFactoryProvider = { SocketFactory.getDefault() },
            maxConnectionsPerClient = 100,
            maxTotalConnections = 2,
            ssrfProtection = false,
        )
        limitServer.start()
        Thread.sleep(300)

        val echoServer = createEchoHttpServer()
        val echoPort = echoServer.localPort
        val sockets = mutableListOf<Socket>()

        try {
            for (i in 0 until 2) {
                val client = Socket(InetAddress.getLoopbackAddress(), limitPort)
                client.soTimeout = 2000
                sockets.add(client)

                client.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
                client.getOutputStream().flush()
                readFully(client.getInputStream(), ByteArray(2))

                val loopback = InetAddress.getLoopbackAddress().address
                val req = ByteArray(4 + loopback.size + 2)
                req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x01
                System.arraycopy(loopback, 0, req, 4, loopback.size)
                req[req.size - 2] = ((echoPort shr 8) and 0xFF).toByte()
                req[req.size - 1] = (echoPort and 0xFF).toByte()
                client.getOutputStream().write(req)
                client.getOutputStream().flush()
                readFully(client.getInputStream(), ByteArray(10))
            }

            Thread.sleep(100)
            val blocked = Socket(InetAddress.getLoopbackAddress(), limitPort)
            blocked.soTimeout = 1000

            val result = try {
                blocked.getInputStream().read()
            } catch (_: Exception) {
                -1
            }
            assertEquals("Connection over total limit should be rejected", -1, result)
            blocked.close()
        } finally {
            sockets.forEach { it.close() }
            echoServer.close()
            limitServer.stop()
        }
    }

    // --- Integration tests ---

    @Test
    fun `CONNECT to echo server via SOCKS5`() {
        val echoServer = createEchoHttpServer()
        val echoPort = echoServer.localPort

        server.start()
        Thread.sleep(500)

        val client = Socket(InetAddress.getLoopbackAddress(), serverPort)
        client.soTimeout = 5000

        // SOCKS5 handshake: negotiate NO_AUTH
        client.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
        client.getOutputStream().flush()

        val negotiateReply = ByteArray(2)
        readFully(client.getInputStream(), negotiateReply)
        assertEquals(0x05.toByte(), negotiateReply[0])
        assertEquals(0x00.toByte(), negotiateReply[1])

        // CONNECT to echo server (IPv4)
        val loopback = InetAddress.getLoopbackAddress().address
        val connectRequest = ByteArray(4 + loopback.size + 2)
        connectRequest[0] = 0x05 // version
        connectRequest[1] = 0x01 // CONNECT
        connectRequest[2] = 0x00 // reserved
        connectRequest[3] = 0x01 // IPv4
        System.arraycopy(loopback, 0, connectRequest, 4, loopback.size)
        connectRequest[connectRequest.size - 2] = ((echoPort shr 8) and 0xFF).toByte()
        connectRequest[connectRequest.size - 1] = (echoPort and 0xFF).toByte()
        client.getOutputStream().write(connectRequest)
        client.getOutputStream().flush()

        // Read CONNECT reply (10 bytes for IPv4)
        val connectReply = ByteArray(10)
        readFully(client.getInputStream(), connectReply)
        assertEquals("Expected success reply", 0x00.toByte(), connectReply[1])

        // Send HTTP request through tunnel
        val httpRequest = "GET / HTTP/1.1\r\nHost: test\r\n\r\n"
        client.getOutputStream().write(httpRequest.toByteArray())
        client.getOutputStream().flush()

        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        val statusLine = reader.readLine()
        assertTrue("Expected 200, got: $statusLine", statusLine?.contains("200") == true)

        client.close()
        echoServer.close()
    }

    @Test
    fun `CONNECT with auth to echo server via SOCKS5`() {
        val echoServer = createEchoHttpServer()
        val echoPort = echoServer.localPort

        val authPort = findFreePort()
        val authServer = Socks5Server(
            bindAddress = InetAddress.getLoopbackAddress(),
            port = authPort,
            socketFactoryProvider = { SocketFactory.getDefault() },
            username = "myuser",
            password = "mypass",
            ssrfProtection = false,
        )
        authServer.start()
        Thread.sleep(500)

        val client = Socket(InetAddress.getLoopbackAddress(), authPort)
        client.soTimeout = 5000

        // Negotiate with USERNAME_PASSWORD
        client.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x02))
        client.getOutputStream().flush()

        val negotiateReply = ByteArray(2)
        readFully(client.getInputStream(), negotiateReply)
        assertEquals(0x05.toByte(), negotiateReply[0])
        assertEquals(0x02.toByte(), negotiateReply[1])

        // Send auth subnegotiation
        val user = "myuser".toByteArray(Charsets.UTF_8)
        val pass = "mypass".toByteArray(Charsets.UTF_8)
        val authReq = byteArrayOf(0x01, user.size.toByte(), *user, pass.size.toByte(), *pass)
        client.getOutputStream().write(authReq)
        client.getOutputStream().flush()

        val authReply = ByteArray(2)
        readFully(client.getInputStream(), authReply)
        assertEquals("Auth version", 0x01.toByte(), authReply[0])
        assertEquals("Auth success", 0x00.toByte(), authReply[1])

        // CONNECT
        val loopback = InetAddress.getLoopbackAddress().address
        val connectRequest = ByteArray(4 + loopback.size + 2)
        connectRequest[0] = 0x05
        connectRequest[1] = 0x01
        connectRequest[2] = 0x00
        connectRequest[3] = 0x01
        System.arraycopy(loopback, 0, connectRequest, 4, loopback.size)
        connectRequest[connectRequest.size - 2] = ((echoPort shr 8) and 0xFF).toByte()
        connectRequest[connectRequest.size - 1] = (echoPort and 0xFF).toByte()
        client.getOutputStream().write(connectRequest)
        client.getOutputStream().flush()

        val connectReply = ByteArray(10)
        readFully(client.getInputStream(), connectReply)
        assertEquals("Expected success", 0x00.toByte(), connectReply[1])

        // Send data through tunnel
        val httpRequest = "GET / HTTP/1.1\r\nHost: test\r\n\r\n"
        client.getOutputStream().write(httpRequest.toByteArray())
        client.getOutputStream().flush()

        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        val statusLine = reader.readLine()
        assertTrue("Expected 200, got: $statusLine", statusLine?.contains("200") == true)

        client.close()
        echoServer.close()
        authServer.stop()
    }

    @Test
    fun `CONNECT with wrong auth is rejected end-to-end`() {
        val authPort = findFreePort()
        val authServer = Socks5Server(
            bindAddress = InetAddress.getLoopbackAddress(),
            port = authPort,
            socketFactoryProvider = { SocketFactory.getDefault() },
            username = "admin",
            password = "secret",
        )
        authServer.start()
        Thread.sleep(300)

        val client = Socket(InetAddress.getLoopbackAddress(), authPort)
        client.soTimeout = 5000

        // Negotiate
        client.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x02))
        client.getOutputStream().flush()
        val negotiateReply = ByteArray(2)
        readFully(client.getInputStream(), negotiateReply)
        assertEquals(0x02.toByte(), negotiateReply[1])

        // Send wrong credentials
        val user = "admin".toByteArray(Charsets.UTF_8)
        val pass = "wrong".toByteArray(Charsets.UTF_8)
        val authReq = byteArrayOf(0x01, user.size.toByte(), *user, pass.size.toByte(), *pass)
        client.getOutputStream().write(authReq)
        client.getOutputStream().flush()

        val authReply = ByteArray(2)
        readFully(client.getInputStream(), authReply)
        assertEquals("Auth should fail", 0x01.toByte(), authReply[1])

        // Connection should be closed after failed auth
        val result = try { client.getInputStream().read() } catch (_: Exception) { -1 }
        assertEquals(-1, result)

        client.close()
        authServer.stop()
    }

    @Test
    fun `CONNECT with domain name uses dnsResolver`() {
        var resolvedHost: String? = null
        val echoServer = createEchoHttpServer()
        val echoPort = echoServer.localPort

        val resolverPort = findFreePort()
        val resolverServer = Socks5Server(
            bindAddress = InetAddress.getLoopbackAddress(),
            port = resolverPort,
            socketFactoryProvider = { SocketFactory.getDefault() },
            dnsResolver = { hostname ->
                resolvedHost = hostname
                InetAddress.getLoopbackAddress()
            },
            ssrfProtection = false,
        )
        resolverServer.start()
        Thread.sleep(500)

        val client = Socket(InetAddress.getLoopbackAddress(), resolverPort)
        client.soTimeout = 5000

        // Negotiate
        client.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
        client.getOutputStream().flush()
        readFully(client.getInputStream(), ByteArray(2))

        // CONNECT with domain "test.local"
        val domain = "test.local".toByteArray(Charsets.US_ASCII)
        val connectRequest = ByteArray(4 + 1 + domain.size + 2)
        connectRequest[0] = 0x05
        connectRequest[1] = 0x01
        connectRequest[2] = 0x00
        connectRequest[3] = 0x03 // domain
        connectRequest[4] = domain.size.toByte()
        System.arraycopy(domain, 0, connectRequest, 5, domain.size)
        connectRequest[connectRequest.size - 2] = ((echoPort shr 8) and 0xFF).toByte()
        connectRequest[connectRequest.size - 1] = (echoPort and 0xFF).toByte()
        client.getOutputStream().write(connectRequest)
        client.getOutputStream().flush()

        val connectReply = ByteArray(10)
        readFully(client.getInputStream(), connectReply)
        assertEquals("Expected success", 0x00.toByte(), connectReply[1])
        assertEquals("test.local", resolvedHost)

        client.close()
        echoServer.close()
        resolverServer.stop()
    }

    @Test
    fun `CONNECT returns failure when no socket factory`() {
        val noNetPort = findFreePort()
        val noNetServer = Socks5Server(
            bindAddress = InetAddress.getLoopbackAddress(),
            port = noNetPort,
            socketFactoryProvider = { null },
        )
        noNetServer.start()
        Thread.sleep(300)

        val client = Socket(InetAddress.getLoopbackAddress(), noNetPort)
        client.soTimeout = 5000

        // Negotiate
        client.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
        client.getOutputStream().flush()
        readFully(client.getInputStream(), ByteArray(2))

        // CONNECT
        val connectRequest = byteArrayOf(
            0x05, 0x01, 0x00, 0x01,
            127.toByte(), 0, 0, 1,
            0x00, 0x50, // port 80
        )
        client.getOutputStream().write(connectRequest)
        client.getOutputStream().flush()

        val reply = ByteArray(10)
        readFully(client.getInputStream(), reply)
        assertEquals("Expected general failure", Socks5Server.REPLY_GENERAL_FAILURE, reply[1])

        client.close()
        noNetServer.stop()
    }

    @Test
    fun `unsupported command returns CMD_NOT_SUPPORTED`() {
        server.start()
        Thread.sleep(300)

        val client = Socket(InetAddress.getLoopbackAddress(), serverPort)
        client.soTimeout = 5000

        // Negotiate
        client.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
        client.getOutputStream().flush()
        readFully(client.getInputStream(), ByteArray(2))

        // BIND command (0x02) — unsupported
        val bindRequest = byteArrayOf(
            0x05, 0x02, 0x00, 0x01,
            127.toByte(), 0, 0, 1,
            0x00, 0x50,
        )
        client.getOutputStream().write(bindRequest)
        client.getOutputStream().flush()

        val reply = ByteArray(10)
        readFully(client.getInputStream(), reply)
        assertEquals("Expected CMD_NOT_SUPPORTED", Socks5Server.REPLY_CMD_NOT_SUPPORTED, reply[1])

        client.close()
    }

    @Test
    fun `negotiate rejects zero methods count`() {
        val input = ByteArrayInputStream(byteArrayOf(0x05, 0x00))
        val output = ByteArrayOutputStream()
        assertFalse(server.negotiate(input, output))
        val response = output.toByteArray()
        assertEquals(0xFF.toByte(), response[1])
    }

    @Test
    fun `negotiate returns false on EOF during methods read`() {
        // Version OK, says 3 methods but only provides 1 byte then EOF
        val input = ByteArrayInputStream(byteArrayOf(0x05, 0x03, 0x00))
        val output = ByteArrayOutputStream()
        // The stream has only 1 method byte but claims 3, so readFully will hit EOF
        assertFalse(server.negotiate(input, output))
    }

    @Test
    fun `readAddress throws on unsupported address type`() {
        // Address type 0x02 is not supported (only 0x01=IPv4, 0x03=domain, 0x04=IPv6)
        val data = byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x50)
        try {
            server.readAddress(ByteArrayInputStream(data))
            assertTrue("Should have thrown IOException", false)
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("Unsupported address type"))
        }
    }

    @Test
    fun `readAddress throws on truncated IPv4 data`() {
        // IPv4 type but only 2 bytes of address instead of 4
        val data = byteArrayOf(0x01, 127.toByte(), 0)
        try {
            server.readAddress(ByteArrayInputStream(data))
            assertTrue("Should have thrown IOException", false)
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("Unexpected end of stream"))
        }
    }

    @Test
    fun `readAddress throws on truncated port`() {
        // Valid IPv4 address but missing port bytes
        val data = byteArrayOf(0x01, 127.toByte(), 0, 0, 1)
        try {
            server.readAddress(ByteArrayInputStream(data))
            assertTrue("Should have thrown IOException", false)
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("closed") || e.message!!.contains("end of stream"))
        }
    }

    @Test
    fun `readAddress throws on invalid domain length zero`() {
        // Domain type with length 0
        val data = byteArrayOf(0x03, 0x00, 0x00, 0x50)
        try {
            server.readAddress(ByteArrayInputStream(data))
            assertTrue("Should have thrown IOException", false)
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("Invalid domain") || e.message!!.contains("length"))
        }
    }

    @Test
    fun `readAddress parses high port numbers correctly`() {
        // IPv4 with port 65535 (0xFF, 0xFF)
        val data = byteArrayOf(
            0x01,
            10.toByte(), 0, 0, 1,
            0xFF.toByte(), 0xFF.toByte(),
        )
        val (host, port) = server.readAddress(ByteArrayInputStream(data))
        assertEquals("10.0.0.1", host)
        assertEquals(65535, port)
    }

    @Test
    fun `readAddress parses port zero`() {
        val data = byteArrayOf(
            0x01,
            10.toByte(), 0, 0, 1,
            0x00, 0x00,
        )
        val (_, port) = server.readAddress(ByteArrayInputStream(data))
        assertEquals(0, port)
    }

    @Test
    fun `readAddress parses full IPv6 address`() {
        // ::ffff:192.168.1.1 (IPv4-mapped IPv6)
        val addr = byteArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0xFF.toByte(), 0xFF.toByte(),
            192.toByte(), 168.toByte(), 1, 1,
        )
        val data = ByteArray(1 + 16 + 2)
        data[0] = 0x04 // IPv6
        System.arraycopy(addr, 0, data, 1, 16)
        data[data.size - 2] = 0x00
        data[data.size - 1] = 0x50
        val (host, port) = server.readAddress(ByteArrayInputStream(data))
        assertTrue("Expected IPv6 address, got: $host", host.isNotEmpty())
        assertEquals(80, port)
    }

    @Test
    fun `sendReply writes IPv6 response`() {
        val output = ByteArrayOutputStream()
        val addr = InetAddress.getByName("::1")
        server.sendReply(output, Socks5Server.REPLY_SUCCESS, addr, 443)
        val reply = output.toByteArray()
        assertEquals(22, reply.size) // 4 header + 16 IPv6 + 2 port
        assertEquals(0x05.toByte(), reply[0])
        assertEquals(0x00.toByte(), reply[1])
        assertEquals(0x04.toByte(), reply[3]) // IPv6 address type
        assertEquals(0x01.toByte(), reply[20]) // port 443 >> 8
        assertEquals(0xBB.toByte(), reply[21]) // port 443 & 0xFF
    }

    @Test
    fun `sendReply writes CONNECTION_REFUSED`() {
        val output = ByteArrayOutputStream()
        server.sendReply(output, Socks5Server.REPLY_CONNECTION_REFUSED)
        val reply = output.toByteArray()
        assertEquals(Socks5Server.REPLY_CONNECTION_REFUSED, reply[1])
    }

    @Test
    fun `sendReply writes HOST_UNREACHABLE`() {
        val output = ByteArrayOutputStream()
        server.sendReply(output, Socks5Server.REPLY_HOST_UNREACHABLE)
        val reply = output.toByteArray()
        assertEquals(Socks5Server.REPLY_HOST_UNREACHABLE, reply[1])
    }

    @Test
    fun `sendReply writes ADDR_NOT_SUPPORTED`() {
        val output = ByteArrayOutputStream()
        server.sendReply(output, Socks5Server.REPLY_ADDR_NOT_SUPPORTED)
        val reply = output.toByteArray()
        assertEquals(Socks5Server.REPLY_ADDR_NOT_SUPPORTED, reply[1])
    }

    @Test
    fun `sendReply port encoding is correct for port 1`() {
        val output = ByteArrayOutputStream()
        server.sendReply(output, Socks5Server.REPLY_SUCCESS, InetAddress.getByName("0.0.0.0"), 1)
        val reply = output.toByteArray()
        assertEquals(0x00.toByte(), reply[8]) // high byte
        assertEquals(0x01.toByte(), reply[9]) // low byte
    }

    @Test
    fun `relay counts bytes across multiple calls`() {
        val data1 = "Hello".toByteArray()
        val data2 = "World".toByteArray()
        server.relay(ByteArrayInputStream(data1), ByteArrayOutputStream())
        server.relay(ByteArrayInputStream(data2), ByteArrayOutputStream())
        assertEquals((data1.size + data2.size).toLong(), server.bytesTransferred)
    }

    @Test
    fun `CONNECT returns CONNECTION_REFUSED for closed port`() {
        // Start server, find a free port, then close it immediately to ensure it's refused
        val closedPort = findFreePort()
        server.start()
        Thread.sleep(300)

        val client = Socket(InetAddress.getLoopbackAddress(), serverPort)
        client.soTimeout = 5000

        // Negotiate
        client.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
        client.getOutputStream().flush()
        readFully(client.getInputStream(), ByteArray(2))

        // CONNECT to the closed port
        val connectRequest = byteArrayOf(
            0x05, 0x01, 0x00, 0x01,
            127.toByte(), 0, 0, 1,
            ((closedPort shr 8) and 0xFF).toByte(),
            (closedPort and 0xFF).toByte(),
        )
        client.getOutputStream().write(connectRequest)
        client.getOutputStream().flush()

        val reply = ByteArray(10)
        readFully(client.getInputStream(), reply)
        assertEquals("Expected CONNECTION_REFUSED", Socks5Server.REPLY_CONNECTION_REFUSED, reply[1])

        client.close()
    }

    @Test
    fun `handleClient closes connection on wrong version in request phase`() {
        server.start()
        Thread.sleep(300)

        val client = Socket(InetAddress.getLoopbackAddress(), serverPort)
        client.soTimeout = 5000

        // Send valid negotiate
        client.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
        client.getOutputStream().flush()
        readFully(client.getInputStream(), ByteArray(2))

        // Send request with wrong version (0x04 instead of 0x05)
        client.getOutputStream().write(byteArrayOf(0x04, 0x01, 0x00, 0x01, 127.toByte(), 0, 0, 1, 0x00, 0x50))
        client.getOutputStream().flush()

        // Server should close the connection
        Thread.sleep(500)
        val result = client.getInputStream().read()
        assertEquals("Expected EOF (connection closed)", -1, result)

        client.close()
    }

    @Test
    fun `companion constants have correct values`() {
        assertEquals(0x05.toByte(), Socks5Server.VERSION)
        assertEquals(0x00.toByte(), Socks5Server.AUTH_NONE)
        assertEquals(0xFF.toByte(), Socks5Server.AUTH_NO_ACCEPTABLE)
        assertEquals(0x01.toByte(), Socks5Server.CMD_CONNECT)
        assertEquals(0x01.toByte(), Socks5Server.ADDR_IPV4)
        assertEquals(0x03.toByte(), Socks5Server.ADDR_DOMAIN)
        assertEquals(0x04.toByte(), Socks5Server.ADDR_IPV6)
        assertEquals(0x00.toByte(), Socks5Server.REPLY_SUCCESS)
        assertEquals(0x01.toByte(), Socks5Server.REPLY_GENERAL_FAILURE)
        assertEquals(0x04.toByte(), Socks5Server.REPLY_HOST_UNREACHABLE)
        assertEquals(0x05.toByte(), Socks5Server.REPLY_CONNECTION_REFUSED)
        assertEquals(0x07.toByte(), Socks5Server.REPLY_CMD_NOT_SUPPORTED)
        assertEquals(0x08.toByte(), Socks5Server.REPLY_ADDR_NOT_SUPPORTED)
    }

    @Test
    fun `CONNECT blocks loopback destination with SSRF protection`() {
        val ssrfPort = findFreePort()
        val ssrfServer = Socks5Server(
            bindAddress = InetAddress.getLoopbackAddress(),
            port = ssrfPort,
            socketFactoryProvider = { SocketFactory.getDefault() },
            ssrfProtection = true,
        )
        ssrfServer.start()
        Thread.sleep(300)

        val client = Socket(InetAddress.getLoopbackAddress(), ssrfPort)
        client.soTimeout = 5000

        // Negotiate
        client.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
        client.getOutputStream().flush()
        readFully(client.getInputStream(), ByteArray(2))

        // CONNECT to loopback (should be blocked)
        val connectRequest = byteArrayOf(
            0x05, 0x01, 0x00, 0x01,
            127.toByte(), 0, 0, 1,
            0x00, 0x50, // port 80
        )
        client.getOutputStream().write(connectRequest)
        client.getOutputStream().flush()

        val reply = ByteArray(10)
        readFully(client.getInputStream(), reply)
        assertEquals("Expected NOT_ALLOWED for loopback", Socks5Server.REPLY_NOT_ALLOWED, reply[1])

        client.close()
        ssrfServer.stop()
    }

    @Test
    fun `isBlockedDestination blocks loopback and link-local`() {
        assertTrue(Socks5Server.isBlockedDestination(InetAddress.getByName("127.0.0.1")))
        assertTrue(Socks5Server.isBlockedDestination(InetAddress.getByName("127.0.0.2")))
        assertTrue(Socks5Server.isBlockedDestination(InetAddress.getByName("::1")))
        assertTrue(Socks5Server.isBlockedDestination(InetAddress.getByName("169.254.1.1")))
        assertFalse(Socks5Server.isBlockedDestination(InetAddress.getByName("8.8.8.8")))
        assertFalse(Socks5Server.isBlockedDestination(InetAddress.getByName("192.168.1.1")))
        assertFalse(Socks5Server.isBlockedDestination(InetAddress.getByName("10.0.0.1")))
    }

    @Test
    fun `CONNECT returns HOST_UNREACHABLE for unresolvable domain`() {
        val failPort = findFreePort()
        val failResolver = Socks5Server(
            bindAddress = InetAddress.getLoopbackAddress(),
            port = failPort,
            socketFactoryProvider = { SocketFactory.getDefault() },
            dnsResolver = { throw java.net.UnknownHostException("nope") },
        )
        failResolver.start()
        Thread.sleep(300)

        val client = Socket(InetAddress.getLoopbackAddress(), failPort)
        client.soTimeout = 5000

        // Negotiate
        client.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
        client.getOutputStream().flush()
        readFully(client.getInputStream(), ByteArray(2))

        // CONNECT with domain
        val domain = "nope.invalid".toByteArray(Charsets.US_ASCII)
        val req = ByteArray(4 + 1 + domain.size + 2)
        req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x03
        req[4] = domain.size.toByte()
        System.arraycopy(domain, 0, req, 5, domain.size)
        req[req.size - 2] = 0x00; req[req.size - 1] = 0x50
        client.getOutputStream().write(req)
        client.getOutputStream().flush()

        val reply = ByteArray(10)
        readFully(client.getInputStream(), reply)
        assertEquals("Expected HOST_UNREACHABLE", Socks5Server.REPLY_HOST_UNREACHABLE, reply[1])

        client.close()
        failResolver.stop()
    }

    // --- handleCachedHttpConnect tests ---

    @Test
    fun `CONNECT to port 80 with httpCache populates cache on miss then serves from cache on hit`() {
        val echoServer = createCacheFriendlyHttpServer()
        val echoPort = echoServer.localPort
        val httpCache = HttpCache()

        val cachePort = findFreePort()
        val cacheServer = Socks5Server(
            bindAddress = InetAddress.getLoopbackAddress(),
            port = cachePort,
            socketFactoryProvider = { createRedirectingSocketFactory(echoPort) },
            dnsResolver = { InetAddress.getLoopbackAddress() },
            httpCache = httpCache,
        )
        cacheServer.start()
        Thread.sleep(500)

        // First connection: cache miss
        run {
            val client = Socket(InetAddress.getLoopbackAddress(), cachePort)
            client.soTimeout = 5000

            // SOCKS5 negotiate
            client.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
            client.getOutputStream().flush()
            readFully(client.getInputStream(), ByteArray(2))

            // CONNECT to port 80 (triggers cache path)
            val domain = "cached-test.local".toByteArray(Charsets.US_ASCII)
            val req = ByteArray(4 + 1 + domain.size + 2)
            req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x03
            req[4] = domain.size.toByte()
            System.arraycopy(domain, 0, req, 5, domain.size)
            req[req.size - 2] = 0x00; req[req.size - 1] = 0x50 // port 80
            client.getOutputStream().write(req)
            client.getOutputStream().flush()
            readFully(client.getInputStream(), ByteArray(10)) // CONNECT reply

            // Send HTTP GET through tunnel
            client.getOutputStream().write("GET / HTTP/1.1\r\nHost: cached-test.local\r\n\r\n".toByteArray())
            client.getOutputStream().flush()

            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val statusLine = reader.readLine()
            assertTrue("Expected 200, got: $statusLine", statusLine?.contains("200") == true)
            client.close()
        }

        Thread.sleep(300) // Let cache entry be stored

        assertEquals("First request should be a cache miss", 1L, httpCache.misses)

        // Second connection: cache hit
        run {
            val client = Socket(InetAddress.getLoopbackAddress(), cachePort)
            client.soTimeout = 5000

            client.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
            client.getOutputStream().flush()
            readFully(client.getInputStream(), ByteArray(2))

            val domain = "cached-test.local".toByteArray(Charsets.US_ASCII)
            val req = ByteArray(4 + 1 + domain.size + 2)
            req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x03
            req[4] = domain.size.toByte()
            System.arraycopy(domain, 0, req, 5, domain.size)
            req[req.size - 2] = 0x00; req[req.size - 1] = 0x50
            client.getOutputStream().write(req)
            client.getOutputStream().flush()
            readFully(client.getInputStream(), ByteArray(10))

            client.getOutputStream().write("GET / HTTP/1.1\r\nHost: cached-test.local\r\n\r\n".toByteArray())
            client.getOutputStream().flush()

            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val statusLine = reader.readLine()
            assertTrue("Expected 200, got: $statusLine", statusLine?.contains("200") == true)
            client.close()
        }

        Thread.sleep(200)
        assertTrue("Second request should be a cache hit", httpCache.hits > 0)

        echoServer.close()
        cacheServer.stop()
    }

    @Test
    fun `CONNECT to port 80 with non-GET request forwards without caching`() {
        val echoServer = createCacheFriendlyPostEchoServer()
        val echoPort = echoServer.localPort
        val httpCache = HttpCache()

        val cachePort = findFreePort()
        val cacheServer = Socks5Server(
            bindAddress = InetAddress.getLoopbackAddress(),
            port = cachePort,
            socketFactoryProvider = { createRedirectingSocketFactory(echoPort) },
            dnsResolver = { InetAddress.getLoopbackAddress() },
            httpCache = httpCache,
        )
        cacheServer.start()
        Thread.sleep(500)

        val client = Socket(InetAddress.getLoopbackAddress(), cachePort)
        client.soTimeout = 5000

        // SOCKS5 negotiate
        client.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
        client.getOutputStream().flush()
        readFully(client.getInputStream(), ByteArray(2))

        // CONNECT to port 80
        val domain = "post-test.local".toByteArray(Charsets.US_ASCII)
        val req = ByteArray(4 + 1 + domain.size + 2)
        req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x03
        req[4] = domain.size.toByte()
        System.arraycopy(domain, 0, req, 5, domain.size)
        req[req.size - 2] = 0x00; req[req.size - 1] = 0x50 // port 80
        client.getOutputStream().write(req)
        client.getOutputStream().flush()
        readFully(client.getInputStream(), ByteArray(10))

        // Send POST through tunnel (non-GET is forwarded directly, not cached)
        val postBody = "data=test"
        client.getOutputStream().write(
            ("POST /submit HTTP/1.1\r\nHost: post-test.local\r\n" +
                "Content-Length: ${postBody.length}\r\n\r\n$postBody").toByteArray(),
        )
        client.getOutputStream().flush()

        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        val statusLine = reader.readLine()
        assertTrue("Expected 200, got: $statusLine", statusLine?.contains("200") == true)

        client.close()
        Thread.sleep(200)

        // POST requests should not be cached
        assertEquals("POST should not produce cache hits", 0L, httpCache.hits)

        echoServer.close()
        cacheServer.stop()
    }

    // --- Additional constantTimeEquals tests ---

    @Test
    fun `constantTimeEquals with unicode characters`() {
        assertTrue(Socks5Server.constantTimeEquals("éèê", "éèê"))
        assertFalse(Socks5Server.constantTimeEquals("éèê", "éèë"))
    }

    @Test
    fun `constantTimeEquals with single character difference`() {
        assertFalse(Socks5Server.constantTimeEquals("password1", "password2"))
        assertFalse(Socks5Server.constantTimeEquals("Apassword", "apassword"))
    }

    // --- Additional sendReply IPv6 tests ---

    @Test
    fun `sendReply with IPv6 encodes full 16-byte address and correct port`() {
        val output = ByteArrayOutputStream()
        val addr = InetAddress.getByName("2001:db8::1")
        server.sendReply(output, Socks5Server.REPLY_SUCCESS, addr, 8080)
        val reply = output.toByteArray()
        assertEquals(22, reply.size) // 4 header + 16 IPv6 + 2 port
        assertEquals(0x05.toByte(), reply[0])
        assertEquals(0x00.toByte(), reply[1]) // success
        assertEquals(0x00.toByte(), reply[2]) // reserved
        assertEquals(0x04.toByte(), reply[3]) // IPv6 address type
        // Port 8080 = 0x1F90
        assertEquals(0x1F.toByte(), reply[20])
        assertEquals(0x90.toByte(), reply[21])
    }

    // --- Helpers ---

    private fun findFreePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val n = input.read(buf, offset, buf.size - offset)
            if (n == -1) throw java.io.IOException("Unexpected EOF")
            offset += n
        }
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

    private fun createCacheFriendlyHttpServer(): ServerSocket {
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
                            val body = "cached content"
                            val response = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/html\r\n" +
                                "Content-Length: ${body.length}\r\n" +
                                "Cache-Control: max-age=3600\r\n" +
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

    private fun createCacheFriendlyPostEchoServer(): ServerSocket {
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        Thread {
            while (!server.isClosed) {
                try {
                    val client = server.accept()
                    Thread {
                        try {
                            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                            var contentLength = 0
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                if (line.lowercase().startsWith("content-length:")) {
                                    contentLength = line.substringAfter(":").trim().toInt()
                                }
                            }
                            if (contentLength > 0) {
                                val bodyChars = CharArray(contentLength)
                                var read = 0
                                while (read < contentLength) {
                                    val n = reader.read(bodyChars, read, contentLength - read)
                                    if (n == -1) break
                                    read += n
                                }
                            }
                            val body = "OK"
                            val response = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/html\r\n" +
                                "Content-Length: ${body.length}\r\n" +
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

    private fun createRedirectingSocketFactory(actualPort: Int): javax.net.SocketFactory {
        return object : javax.net.SocketFactory() {
            override fun createSocket(): Socket = Socket()
            override fun createSocket(host: String?, port: Int): Socket = Socket(host, actualPort)
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
                Socket(host, actualPort)
            override fun createSocket(host: InetAddress?, port: Int): Socket = Socket(host, actualPort)
            override fun createSocket(host: InetAddress?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
                Socket(host, actualPort)
        }
    }
}
