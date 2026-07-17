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
}
