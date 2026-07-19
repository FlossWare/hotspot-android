package org.flossware.hotspot.proxy

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory

class UdpRelayTest {

    private lateinit var relay: UdpRelay
    private var relayInitialized = false

    @Before
    fun setUp() {
        relayInitialized = false
    }

    @After
    fun tearDown() {
        if (relayInitialized) relay.stop()
    }

    // ---- Frame parsing: IPv4 ----

    @Test
    fun `parseUdpFrame parses valid IPv4 frame`() {
        relay = createRelay()
        val data = byteArrayOf(
            0x00, 0x00,         // RSV
            0x00,               // FRAG
            0x01,               // ATYP = IPv4
            10, 0, 0, 1,       // DST.ADDR
            0x00, 0x50,         // DST.PORT = 80
            0x48, 0x65, 0x6C, 0x6C, 0x6F, // "Hello"
        )
        val frame = relay.parseUdpFrame(data)
        assertNotNull(frame)
        assertEquals(0.toByte(), frame!!.frag)
        assertEquals(0x01.toByte(), frame.addrType)
        assertEquals("10.0.0.1", frame.dstAddr)
        assertEquals(80, frame.dstPort)
        assertArrayEquals("Hello".toByteArray(), frame.payload)
    }

    @Test
    fun `parseUdpFrame parses IPv4 frame with no payload`() {
        relay = createRelay()
        val data = byteArrayOf(
            0x00, 0x00, 0x00, 0x01,
            10, 0, 0, 1,
            0x00, 0x50,
        )
        val frame = relay.parseUdpFrame(data)
        assertNotNull(frame)
        assertEquals(0, frame!!.payload.size)
    }

    @Test
    fun `parseUdpFrame parses IPv4 high port correctly`() {
        relay = createRelay()
        val data = byteArrayOf(
            0x00, 0x00, 0x00, 0x01,
            10, 0, 0, 1,
            0xFF.toByte(), 0xFF.toByte(), // port 65535
        )
        val frame = relay.parseUdpFrame(data)
        assertNotNull(frame)
        assertEquals(65535, frame!!.dstPort)
    }

    // ---- Frame parsing: domain ----

    @Test
    fun `parseUdpFrame parses valid domain frame`() {
        relay = createRelay()
        val domain = "example.com".toByteArray(Charsets.US_ASCII)
        val payload = "test".toByteArray()
        val data = ByteArray(4 + 1 + domain.size + 2 + payload.size)
        data[0] = 0x00; data[1] = 0x00; data[2] = 0x00; data[3] = 0x03
        data[4] = domain.size.toByte()
        System.arraycopy(domain, 0, data, 5, domain.size)
        val portOffset = 5 + domain.size
        data[portOffset] = 0x01; data[portOffset + 1] = 0xBB.toByte() // port 443
        System.arraycopy(payload, 0, data, portOffset + 2, payload.size)

        val frame = relay.parseUdpFrame(data)
        assertNotNull(frame)
        assertEquals(0x03.toByte(), frame!!.addrType)
        assertEquals("example.com", frame.dstAddr)
        assertEquals(443, frame.dstPort)
        assertArrayEquals(payload, frame.payload)
    }

    @Test
    fun `parseUdpFrame returns null for empty domain length`() {
        relay = createRelay()
        val data = byteArrayOf(
            0x00, 0x00, 0x00, 0x03,
            0x00, // domain length = 0
            0x00, 0x50,
        )
        assertNull(relay.parseUdpFrame(data))
    }

    // ---- Frame parsing: IPv6 ----

    @Test
    fun `parseUdpFrame parses valid IPv6 frame`() {
        relay = createRelay()
        val addr = ByteArray(16)
        addr[15] = 1 // ::1
        val payload = byteArrayOf(0x41, 0x42, 0x43, 0x44)
        val data = ByteArray(4 + 16 + 2 + payload.size)
        data[0] = 0x00; data[1] = 0x00; data[2] = 0x00; data[3] = 0x04
        System.arraycopy(addr, 0, data, 4, 16)
        data[20] = 0x1F; data[21] = 0x90.toByte() // port 8080
        System.arraycopy(payload, 0, data, 22, payload.size)

        val frame = relay.parseUdpFrame(data)
        assertNotNull(frame)
        assertEquals(0x04.toByte(), frame!!.addrType)
        assertEquals(8080, frame.dstPort)
        assertArrayEquals(payload, frame.payload)
    }

    // ---- Frame parsing: error cases ----

    @Test
    fun `parseUdpFrame returns null for short data`() {
        relay = createRelay()
        assertNull(relay.parseUdpFrame(byteArrayOf(0x00)))
        assertNull(relay.parseUdpFrame(byteArrayOf(0x00, 0x00)))
        assertNull(relay.parseUdpFrame(byteArrayOf(0x00, 0x00, 0x00)))
    }

    @Test
    fun `parseUdpFrame returns null for unsupported ATYP`() {
        relay = createRelay()
        val data = byteArrayOf(
            0x00, 0x00, 0x00, 0x02,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x50,
        )
        assertNull(relay.parseUdpFrame(data))
    }

    @Test
    fun `parseUdpFrame returns null for truncated IPv4`() {
        relay = createRelay()
        val data = byteArrayOf(
            0x00, 0x00, 0x00, 0x01,
            10, 0, // only 2 of 4 address bytes
        )
        assertNull(relay.parseUdpFrame(data))
    }

    @Test
    fun `parseUdpFrame returns null for truncated IPv6`() {
        relay = createRelay()
        val data = byteArrayOf(
            0x00, 0x00, 0x00, 0x04,
            0, 0, 0, 0, 0, 0, 0, 0, // only 8 of 16 address bytes
        )
        assertNull(relay.parseUdpFrame(data))
    }

    @Test
    fun `parseUdpFrame returns null for truncated domain data`() {
        relay = createRelay()
        val data = byteArrayOf(
            0x00, 0x00, 0x00, 0x03,
            0x0A, // domain length = 10
            0x61, 0x62, 0x63, // only 3 of 10 domain bytes
        )
        assertNull(relay.parseUdpFrame(data))
    }

    // ---- Fragment rejection ----

    @Test
    fun `parseUdpFrame preserves non-zero FRAG`() {
        relay = createRelay()
        val data = byteArrayOf(
            0x00, 0x00, 0x01, 0x01,
            10, 0, 0, 1,
            0x00, 0x50,
            0x48,
        )
        val frame = relay.parseUdpFrame(data)
        assertNotNull(frame)
        assertEquals(1.toByte(), frame!!.frag)
    }

    @Test
    fun `handleClientFrame drops fragmented frame without creating mapping`() {
        relay = createRelay()
        relay.start()
        Thread.sleep(200)

        val data = byteArrayOf(
            0x00, 0x00, 0x02, 0x01, // FRAG = 2
            10, 0, 0, 1, 0x00, 0x50, 0x48,
        )
        relay.handleClientFrame(data, InetAddress.getLoopbackAddress(), 12345)
        assertEquals(0, relay.mappingCount)
    }

    // ---- buildUdpFrame ----

    @Test
    fun `buildUdpFrame creates correct IPv4 frame`() {
        relay = createRelay()
        val addr = InetAddress.getByName("10.0.0.1")
        val payload = "test".toByteArray()
        val frame = relay.buildUdpFrame(addr, 8080, payload)

        assertEquals(4 + 4 + 2 + 4, frame.size)
        assertEquals(0x00.toByte(), frame[0]) // RSV
        assertEquals(0x00.toByte(), frame[1]) // RSV
        assertEquals(0x00.toByte(), frame[2]) // FRAG
        assertEquals(0x01.toByte(), frame[3]) // ATYP = IPv4
        assertEquals(10.toByte(), frame[4])
        assertEquals(0x1F.toByte(), frame[8]) // 8080 >> 8
        assertEquals(0x90.toByte(), frame[9]) // 8080 & 0xFF
        assertEquals('t'.code.toByte(), frame[10])
    }

    @Test
    fun `buildUdpFrame creates correct IPv6 frame`() {
        relay = createRelay()
        val addr = InetAddress.getByName("::1")
        val payload = "hi".toByteArray()
        val frame = relay.buildUdpFrame(addr, 443, payload)

        assertEquals(4 + 16 + 2 + 2, frame.size)
        assertEquals(0x04.toByte(), frame[3]) // ATYP = IPv6
        assertEquals(0x01.toByte(), frame[20]) // 443 >> 8
        assertEquals(0xBB.toByte(), frame[21]) // 443 & 0xFF
    }

    @Test
    fun `buildUdpFrame roundtrips through parseUdpFrame`() {
        relay = createRelay()
        val addr = InetAddress.getByName("8.8.8.8")
        val payload = "round-trip".toByteArray()
        val frame = relay.buildUdpFrame(addr, 53, payload)

        val parsed = relay.parseUdpFrame(frame)
        assertNotNull(parsed)
        assertEquals("8.8.8.8", parsed!!.dstAddr)
        assertEquals(53, parsed.dstPort)
        assertArrayEquals(payload, parsed.payload)
    }

    // ---- Lifecycle ----

    @Test
    fun `start sets running and binds port`() {
        relay = createRelay()
        assertTrue(relay.start())
        assertTrue(relay.isRunning)
        assertTrue("Port should be positive", relay.port > 0)
    }

    @Test
    fun `stop sets not running`() {
        relay = createRelay()
        relay.start()
        Thread.sleep(200)
        relay.stop()
        assertFalse(relay.isRunning)
    }

    @Test
    fun `double start is idempotent`() {
        relay = createRelay()
        assertTrue(relay.start())
        assertTrue(relay.start())
        assertTrue(relay.isRunning)
    }

    @Test
    fun `mappingCount starts at zero`() {
        relay = createRelay()
        assertEquals(0, relay.mappingCount)
    }

    // ---- Mapping timeout ----

    @Test
    fun `idle mappings are cleaned up after timeout`() {
        val activeMappings = AtomicInteger(0)
        relayInitialized = true
        relay = UdpRelay(
            bindAddress = InetAddress.getLoopbackAddress(),
            ssrfProtection = false,
            activeMappings = activeMappings,
            mappingTimeoutMs = 200L,
        )
        relay.start()
        Thread.sleep(200)

        val echoServer = createUdpEchoServer()

        // Send a UDP frame to create a mapping
        val payload = "timeout-test".toByteArray()
        val frame = buildSocks5UdpFrame(
            InetAddress.getLoopbackAddress(), echoServer.localPort, payload,
        )
        val client = DatagramSocket()
        client.soTimeout = 3000
        client.send(
            DatagramPacket(frame, frame.size, InetAddress.getLoopbackAddress(), relay.port),
        )

        val resp = ByteArray(UdpRelay.MAX_UDP_FRAME_SIZE)
        client.receive(DatagramPacket(resp, resp.size))

        assertEquals(1, relay.mappingCount)
        assertEquals(1, activeMappings.get())

        // Wait for timeout + cleanup cycle
        Thread.sleep(600)

        assertEquals(0, relay.mappingCount)
        assertEquals(0, activeMappings.get())

        client.close()
        echoServer.close()
    }

    // ---- Mapping pool limit ----

    @Test
    fun `mapping pool rejects when at capacity`() {
        val activeMappings = AtomicInteger(0)
        relayInitialized = true
        relay = UdpRelay(
            bindAddress = InetAddress.getLoopbackAddress(),
            ssrfProtection = false,
            activeMappings = activeMappings,
            maxMappings = 2,
            mappingTimeoutMs = 60_000L,
        )
        relay.start()
        Thread.sleep(200)

        val echoServers = (0 until 3).map { createUdpEchoServer() }

        val client = DatagramSocket()
        client.soTimeout = 2000

        // First two destinations should succeed
        for (i in 0 until 2) {
            val payload = "msg-$i".toByteArray()
            val frame = buildSocks5UdpFrame(
                InetAddress.getLoopbackAddress(), echoServers[i].localPort, payload,
            )
            client.send(
                DatagramPacket(frame, frame.size, InetAddress.getLoopbackAddress(), relay.port),
            )
            val resp = ByteArray(UdpRelay.MAX_UDP_FRAME_SIZE)
            client.receive(DatagramPacket(resp, resp.size))
        }

        assertEquals(2, relay.mappingCount)

        // Third destination should be dropped (at capacity)
        val payload = "msg-2".toByteArray()
        val frame = buildSocks5UdpFrame(
            InetAddress.getLoopbackAddress(), echoServers[2].localPort, payload,
        )
        client.send(
            DatagramPacket(frame, frame.size, InetAddress.getLoopbackAddress(), relay.port),
        )

        try {
            val resp = ByteArray(UdpRelay.MAX_UDP_FRAME_SIZE)
            client.receive(DatagramPacket(resp, resp.size))
            assertTrue("Should not receive response at capacity", false)
        } catch (_: java.net.SocketTimeoutException) {
            // Expected: dropped because pool is full
        }

        assertEquals(2, relay.mappingCount)

        client.close()
        echoServers.forEach { it.close() }
    }

    // ---- SSRF protection ----

    @Test
    fun `SSRF protection blocks loopback destination`() {
        relayInitialized = true
        relay = UdpRelay(
            bindAddress = InetAddress.getLoopbackAddress(),
            ssrfProtection = true,
        )
        relay.start()
        Thread.sleep(200)

        val frame = buildSocks5UdpFrame(
            InetAddress.getLoopbackAddress(), 8080, "blocked".toByteArray(),
        )
        val client = DatagramSocket()
        client.soTimeout = 1000
        client.send(
            DatagramPacket(frame, frame.size, InetAddress.getLoopbackAddress(), relay.port),
        )

        try {
            val resp = ByteArray(UdpRelay.MAX_UDP_FRAME_SIZE)
            client.receive(DatagramPacket(resp, resp.size))
            assertTrue("Should not receive response for blocked destination", false)
        } catch (_: java.net.SocketTimeoutException) {
            // Expected
        }

        assertEquals(0, relay.mappingCount)
        client.close()
    }

    // ---- End-to-end: client -> UDP ASSOCIATE -> UDP frame -> echo server -> response ----

    @Test
    fun `end-to-end UDP relay via SOCKS5 UDP ASSOCIATE`() {
        val echoServer = createUdpEchoServer()
        val echoPort = echoServer.localPort

        val serverPort = findFreePort()
        val socks5 = Socks5Server(
            bindAddress = InetAddress.getLoopbackAddress(),
            port = serverPort,
            socketFactoryProvider = { SocketFactory.getDefault() },
            ssrfProtection = false,
        )
        socks5.start()
        Thread.sleep(500)

        val tcpClient = Socket(InetAddress.getLoopbackAddress(), serverPort)
        tcpClient.soTimeout = 5000

        try {
            // SOCKS5 negotiation
            tcpClient.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
            tcpClient.getOutputStream().flush()
            val negotiateReply = ByteArray(2)
            readFully(tcpClient.getInputStream(), negotiateReply)
            assertEquals(0x05.toByte(), negotiateReply[0])
            assertEquals(0x00.toByte(), negotiateReply[1])

            // UDP ASSOCIATE request (CMD=0x03, DST.ADDR=0.0.0.0:0)
            val assocRequest = byteArrayOf(
                0x05, 0x03, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00,
            )
            tcpClient.getOutputStream().write(assocRequest)
            tcpClient.getOutputStream().flush()

            // Read SOCKS5 reply (10 bytes for IPv4 bind address)
            val assocReply = ByteArray(10)
            readFully(tcpClient.getInputStream(), assocReply)
            assertEquals("Expected success", 0x00.toByte(), assocReply[1])

            // Extract relay port from BND.PORT
            val relayPort = ((assocReply[8].toInt() and 0xFF) shl 8) or
                (assocReply[9].toInt() and 0xFF)
            assertTrue("Relay port should be positive", relayPort > 0)

            // Build and send SOCKS5 UDP frame to the relay
            val payload = "Hello UDP".toByteArray()
            val udpFrame = buildSocks5UdpFrame(
                InetAddress.getLoopbackAddress(), echoPort, payload,
            )

            val udpClient = DatagramSocket()
            udpClient.soTimeout = 5000
            udpClient.send(
                DatagramPacket(
                    udpFrame, udpFrame.size,
                    InetAddress.getLoopbackAddress(), relayPort,
                ),
            )

            // Receive SOCKS5 UDP response frame
            val respBuf = ByteArray(UdpRelay.MAX_UDP_FRAME_SIZE)
            val respPacket = DatagramPacket(respBuf, respBuf.size)
            udpClient.receive(respPacket)

            val respData = respPacket.data.copyOf(respPacket.length)
            assertTrue("Response should be non-empty", respData.size > 10)

            // Verify SOCKS5 UDP frame header
            assertEquals(0x00.toByte(), respData[0]) // RSV
            assertEquals(0x00.toByte(), respData[1]) // RSV
            assertEquals(0x00.toByte(), respData[2]) // FRAG

            // Extract payload from response frame
            val respAtyp = respData[3].toInt() and 0xFF
            val payloadOffset = when (respAtyp) {
                0x01 -> 4 + 4 + 2  // IPv4: header + addr + port
                0x04 -> 4 + 16 + 2 // IPv6: header + addr + port
                else -> throw AssertionError("Unexpected ATYP in response: $respAtyp")
            }
            val respPayload = respData.copyOfRange(payloadOffset, respData.size)
            assertArrayEquals("Echo payload should match", payload, respPayload)

            udpClient.close()
        } finally {
            tcpClient.close()
            Thread.sleep(500) // allow teardown to propagate
            socks5.stop()
            echoServer.close()
        }
    }

    @Test
    fun `UDP ASSOCIATE teardown on TCP close`() {
        val serverPort = findFreePort()
        val socks5 = Socks5Server(
            bindAddress = InetAddress.getLoopbackAddress(),
            port = serverPort,
            socketFactoryProvider = { SocketFactory.getDefault() },
            ssrfProtection = false,
        )
        socks5.start()
        Thread.sleep(500)

        val tcpClient = Socket(InetAddress.getLoopbackAddress(), serverPort)
        tcpClient.soTimeout = 5000

        // Negotiate + UDP ASSOCIATE
        tcpClient.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
        tcpClient.getOutputStream().flush()
        readFully(tcpClient.getInputStream(), ByteArray(2))

        tcpClient.getOutputStream().write(
            byteArrayOf(0x05, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
        )
        tcpClient.getOutputStream().flush()

        val reply = ByteArray(10)
        readFully(tcpClient.getInputStream(), reply)
        assertEquals("Expected success", 0x00.toByte(), reply[1])

        val relayPort = ((reply[8].toInt() and 0xFF) shl 8) or (reply[9].toInt() and 0xFF)
        assertTrue("Relay port should be positive", relayPort > 0)

        // Close TCP => relay should tear down
        tcpClient.close()
        Thread.sleep(1000) // allow teardown

        // Sending to the relay port should now fail (socket closed)
        val udpClient = DatagramSocket()
        udpClient.soTimeout = 1000
        val frame = buildSocks5UdpFrame(
            InetAddress.getLoopbackAddress(), 8080, "after-close".toByteArray(),
        )
        udpClient.send(
            DatagramPacket(frame, frame.size, InetAddress.getLoopbackAddress(), relayPort),
        )
        try {
            val resp = ByteArray(UdpRelay.MAX_UDP_FRAME_SIZE)
            udpClient.receive(DatagramPacket(resp, resp.size))
            // If somehow a response arrives, the relay is still up (unexpected)
            assertTrue("Relay should be torn down after TCP close", false)
        } catch (_: java.net.SocketTimeoutException) {
            // Expected: relay is gone
        }

        udpClient.close()
        socks5.stop()
    }

    // ---- Data type tests ----

    @Test
    fun `UdpFrame equals and hashCode`() {
        val f1 = UdpRelay.UdpFrame(0, 0x01, "10.0.0.1", 80, "hello".toByteArray())
        val f2 = UdpRelay.UdpFrame(0, 0x01, "10.0.0.1", 80, "hello".toByteArray())
        val f3 = UdpRelay.UdpFrame(0, 0x01, "10.0.0.2", 80, "hello".toByteArray())
        val f4 = UdpRelay.UdpFrame(0, 0x01, "10.0.0.1", 81, "hello".toByteArray())
        val f5 = UdpRelay.UdpFrame(0, 0x01, "10.0.0.1", 80, "world".toByteArray())

        assertEquals(f1, f2)
        assertEquals(f1.hashCode(), f2.hashCode())
        assertFalse(f1 == f3)
        assertFalse(f1 == f4)
        assertFalse(f1 == f5)
    }

    @Test
    fun `MappingKey equals and hashCode`() {
        val k1 = UdpRelay.MappingKey(InetAddress.getByName("10.0.0.1"), 80)
        val k2 = UdpRelay.MappingKey(InetAddress.getByName("10.0.0.1"), 80)
        val k3 = UdpRelay.MappingKey(InetAddress.getByName("10.0.0.2"), 80)
        val k4 = UdpRelay.MappingKey(InetAddress.getByName("10.0.0.1"), 81)

        assertEquals(k1, k2)
        assertEquals(k1.hashCode(), k2.hashCode())
        assertNotEquals(k1, k3)
        assertNotEquals(k1, k4)
    }

    @Test
    fun `companion constants have correct values`() {
        assertEquals(1000, UdpRelay.MAX_MAPPINGS)
        assertEquals(60_000L, UdpRelay.MAPPING_TIMEOUT_MS)
        assertEquals(65535, UdpRelay.MAX_UDP_FRAME_SIZE)
    }

    @Test
    fun `CMD_UDP_ASSOCIATE constant is 0x03`() {
        assertEquals(0x03.toByte(), Socks5Server.CMD_UDP_ASSOCIATE)
    }

    // ---- Helpers ----

    private fun createRelay(): UdpRelay {
        relayInitialized = true
        return UdpRelay(
            bindAddress = InetAddress.getLoopbackAddress(),
            ssrfProtection = false,
        )
    }

    private fun buildSocks5UdpFrame(
        dstAddr: InetAddress,
        dstPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val addrBytes = dstAddr.address
        val addrType: Byte = if (dstAddr is Inet6Address) 0x04 else 0x01
        val frame = ByteArray(4 + addrBytes.size + 2 + payload.size)
        frame[0] = 0x00 // RSV
        frame[1] = 0x00 // RSV
        frame[2] = 0x00 // FRAG
        frame[3] = addrType
        System.arraycopy(addrBytes, 0, frame, 4, addrBytes.size)
        val portOffset = 4 + addrBytes.size
        frame[portOffset] = ((dstPort shr 8) and 0xFF).toByte()
        frame[portOffset + 1] = (dstPort and 0xFF).toByte()
        System.arraycopy(payload, 0, frame, portOffset + 2, payload.size)
        return frame
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private fun createUdpEchoServer(): DatagramSocket {
        val server = DatagramSocket(0, InetAddress.getLoopbackAddress())
        Thread {
            val buffer = ByteArray(UdpRelay.MAX_UDP_FRAME_SIZE)
            while (!server.isClosed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    server.soTimeout = 1000
                    server.receive(packet)
                    val echo = DatagramPacket(
                        packet.data, packet.length,
                        packet.address, packet.port,
                    )
                    server.send(echo)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    break
                }
            }
        }.apply { isDaemon = true; start() }
        return server
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
}
