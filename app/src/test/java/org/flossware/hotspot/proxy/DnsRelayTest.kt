package org.flossware.hotspot.proxy

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket

class DnsRelayTest {

    private lateinit var relay: DnsRelay
    private var relayPort = 0
    private var upstreamPort = 0
    private var upstreamServer: DatagramSocket? = null

    @Before
    fun setUp() {
        relayPort = findFreePort()
        upstreamPort = findFreePort()
    }

    @After
    fun tearDown() {
        relay.stop()
        upstreamServer?.close()
    }

    @Test
    fun `start sets running to true`() {
        relay = createRelay()
        relay.start()
        Thread.sleep(200)
        assertTrue(relay.isRunning)
    }

    @Test
    fun `stop sets running to false`() {
        relay = createRelay()
        relay.start()
        Thread.sleep(200)
        relay.stop()
        Thread.sleep(200)
        assertFalse(relay.isRunning)
    }

    @Test
    fun `double start is idempotent`() {
        relay = createRelay()
        relay.start()
        relay.start()
        Thread.sleep(200)
        assertTrue(relay.isRunning)
    }

    @Test
    fun `forwards DNS query to upstream and returns response`() {
        upstreamServer = createMockDnsServer(upstreamPort)
        relay = createRelay()
        relay.start()
        Thread.sleep(500)

        val query = byteArrayOf(0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

        val client = DatagramSocket()
        val queryPacket = DatagramPacket(
            query, query.size,
            InetAddress.getLoopbackAddress(), relayPort,
        )
        client.send(queryPacket)
        client.soTimeout = 5000

        val responseBuffer = ByteArray(4096)
        val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
        client.receive(responsePacket)

        assertTrue("Should receive response", responsePacket.length > 0)
        assertEquals(MOCK_RESPONSE.size, responsePacket.length)
        for (i in MOCK_RESPONSE.indices) {
            assertEquals("Byte $i mismatch", MOCK_RESPONSE[i], responsePacket.data[i])
        }

        client.close()
    }

    @Test
    fun `handles multiple concurrent queries`() {
        upstreamServer = createMockDnsServer(upstreamPort)
        relay = createRelay()
        relay.start()
        Thread.sleep(500)

        val clients = (1..5).map { i ->
            Thread {
                val client = DatagramSocket()
                val query = byteArrayOf(i.toByte(), 0x01, 0x01, 0x00)
                val packet = DatagramPacket(
                    query, query.size,
                    InetAddress.getLoopbackAddress(), relayPort,
                )
                client.send(packet)
                client.soTimeout = 5000

                val response = ByteArray(4096)
                val responsePacket = DatagramPacket(response, response.size)
                client.receive(responsePacket)
                assertTrue(responsePacket.length > 0)
                client.close()
            }
        }
        clients.forEach { it.start() }
        clients.forEach { it.join(10_000) }
    }

    @Test
    fun `socketBinder is called for upstream socket`() {
        var binderCalled = false
        upstreamServer = createMockDnsServer(upstreamPort)
        relay = DnsRelay(
            bindAddress = InetAddress.getLoopbackAddress(),
            listenPort = relayPort,
            upstreamDnsProvider = { InetAddress.getLoopbackAddress() },
            upstreamPort = upstreamPort,
            socketBinder = { binderCalled = true },
        )
        relay.start()
        Thread.sleep(500)

        val client = DatagramSocket()
        val query = byteArrayOf(0x00, 0x01)
        client.send(DatagramPacket(query, query.size, InetAddress.getLoopbackAddress(), relayPort))
        client.soTimeout = 5000

        val response = ByteArray(4096)
        client.receive(DatagramPacket(response, response.size))
        client.close()

        assertTrue("Socket binder should have been called", binderCalled)
    }

    @Test
    fun `stop closes socket and thread`() {
        relay = createRelay()
        relay.start()
        Thread.sleep(200)
        relay.stop()
        Thread.sleep(500)
        assertFalse(relay.isRunning)
    }

    private fun createRelay(): DnsRelay {
        return DnsRelay(
            bindAddress = InetAddress.getLoopbackAddress(),
            listenPort = relayPort,
            upstreamDnsProvider = { InetAddress.getLoopbackAddress() },
            upstreamPort = upstreamPort,
        )
    }

    private fun findFreePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }

    private fun createMockDnsServer(port: Int): DatagramSocket {
        val server = DatagramSocket(port, InetAddress.getLoopbackAddress())
        Thread {
            val buffer = ByteArray(4096)
            while (!server.isClosed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    server.soTimeout = 1000
                    server.receive(packet)
                    val response = DatagramPacket(
                        MOCK_RESPONSE, MOCK_RESPONSE.size,
                        packet.address, packet.port,
                    )
                    server.send(response)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    break
                }
            }
        }.apply { isDaemon = true; start() }
        return server
    }

    companion object {
        private val MOCK_RESPONSE = byteArrayOf(
            0x00, 0x01, // Transaction ID
            0x81.toByte(), 0x80.toByte(), // Flags: response
            0x00, 0x01, // Questions: 1
            0x00, 0x01, // Answers: 1
            0x00, 0x00, // Authority: 0
            0x00, 0x00, // Additional: 0
        )
    }
}
