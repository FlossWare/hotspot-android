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

    @Test
    fun `cache hits are zero initially`() {
        relay = createRelay()
        assertEquals(0L, relay.cacheHits)
        assertEquals(0L, relay.cacheMisses)
    }

    @Test
    fun `second identical query is served from cache`() {
        upstreamServer = createMockDnsServer(upstreamPort)
        relay = createRelay()
        relay.start()
        Thread.sleep(500)

        val query = buildDnsQuery(0x00, 0x01, "example.com")

        val client = DatagramSocket()
        client.soTimeout = 5000

        // First query — cache miss
        val q1 = DatagramPacket(query, query.size, InetAddress.getLoopbackAddress(), relayPort)
        client.send(q1)
        val resp1 = ByteArray(4096)
        client.receive(DatagramPacket(resp1, resp1.size))

        Thread.sleep(100)
        assertEquals(0L, relay.cacheHits)
        assertEquals(1L, relay.cacheMisses)

        // Second identical query — cache hit
        val q2 = DatagramPacket(query, query.size, InetAddress.getLoopbackAddress(), relayPort)
        client.send(q2)
        val resp2 = ByteArray(4096)
        client.receive(DatagramPacket(resp2, resp2.size))

        Thread.sleep(100)
        assertEquals(1L, relay.cacheHits)
        assertEquals(1L, relay.cacheMisses)

        client.close()
    }

    @Test
    fun `extractQuestionKey returns null for short queries`() {
        relay = createRelay()
        val shortData = byteArrayOf(0x00, 0x01)
        val key = relay.extractQuestionKey(shortData)
        assertEquals(null, key)
    }

    @Test
    fun `extractQuestionKey returns null for zero question count`() {
        relay = createRelay()
        val data = byteArrayOf(0x00, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val key = relay.extractQuestionKey(data)
        assertEquals(null, key)
    }

    @Test
    fun `extractQuestionKey returns key for valid query`() {
        relay = createRelay()
        val query = buildDnsQuery(0xAB, 0xCD, "test.com")
        val key = relay.extractQuestionKey(query)
        assertTrue("Should extract a key", key != null)
    }

    @Test
    fun `extractQuestionKey ignores transaction ID`() {
        relay = createRelay()
        val query1 = buildDnsQuery(0x00, 0x01, "test.com")
        val query2 = buildDnsQuery(0xFF, 0xFE, "test.com")
        val key1 = relay.extractQuestionKey(query1)
        val key2 = relay.extractQuestionKey(query2)
        assertEquals("Same question with different txn IDs should produce same key", key1, key2)
    }

    @Test
    fun `extractMinTtl returns default for short data`() {
        relay = createRelay()
        assertEquals(DnsRelay.DEFAULT_TTL, relay.extractMinTtl(byteArrayOf(0x00)))
    }

    @Test
    fun `extractMinTtl returns default for zero answer count`() {
        relay = createRelay()
        val data = ByteArray(12)
        assertEquals(DnsRelay.DEFAULT_TTL, relay.extractMinTtl(data))
    }

    @Test
    fun `extractMinTtl parses TTL from answer record`() {
        relay = createRelay()
        val response = buildDnsResponseWithTtl("example.com", 300, byteArrayOf(1, 2, 3, 4))
        val ttl = relay.extractMinTtl(response)
        assertEquals(300, ttl)
    }

    @Test
    fun `extractMinTtl clamps below MIN_TTL`() {
        relay = createRelay()
        val response = buildDnsResponseWithTtl("example.com", 1, byteArrayOf(1, 2, 3, 4))
        val ttl = relay.extractMinTtl(response)
        assertEquals(DnsRelay.MIN_TTL, ttl)
    }

    @Test
    fun `extractMinTtl clamps above MAX_TTL`() {
        relay = createRelay()
        val response = buildDnsResponseWithTtl("example.com", 999999, byteArrayOf(1, 2, 3, 4))
        val ttl = relay.extractMinTtl(response)
        assertEquals(DnsRelay.MAX_TTL, ttl)
    }

    @Test
    fun `cache constants are correct`() {
        relay = createRelay()
        assertEquals(1000, DnsRelay.MAX_CACHE_SIZE)
        assertEquals(60, DnsRelay.DEFAULT_TTL)
        assertEquals(10, DnsRelay.MIN_TTL)
        assertEquals(3600, DnsRelay.MAX_TTL)
    }

    @Test
    fun `stop clears cache`() {
        upstreamServer = createMockDnsServer(upstreamPort)
        relay = createRelay()
        relay.start()
        Thread.sleep(500)

        val query = buildDnsQuery(0x00, 0x01, "example.com")
        val client = DatagramSocket()
        client.soTimeout = 5000
        client.send(DatagramPacket(query, query.size, InetAddress.getLoopbackAddress(), relayPort))
        val resp = ByteArray(4096)
        client.receive(DatagramPacket(resp, resp.size))
        client.close()

        Thread.sleep(100)
        assertEquals(1L, relay.cacheMisses)

        relay.stop()
        Thread.sleep(200)

        assertEquals(0L, relay.cacheHits)
    }

    private fun buildDnsQuery(txnHi: Int, txnLo: Int, domain: String): ByteArray {
        val out = mutableListOf<Byte>()
        out.add(txnHi.toByte())
        out.add(txnLo.toByte())
        // Flags: standard query
        out.add(0x01); out.add(0x00)
        // QDCOUNT: 1
        out.add(0x00); out.add(0x01)
        // ANCOUNT: 0
        out.add(0x00); out.add(0x00)
        // NSCOUNT: 0
        out.add(0x00); out.add(0x00)
        // ARCOUNT: 0
        out.add(0x00); out.add(0x00)
        // Question section
        for (label in domain.split(".")) {
            out.add(label.length.toByte())
            for (ch in label) out.add(ch.code.toByte())
        }
        out.add(0x00) // null terminator
        // QTYPE: A (1)
        out.add(0x00); out.add(0x01)
        // QCLASS: IN (1)
        out.add(0x00); out.add(0x01)
        return out.toByteArray()
    }

    private fun buildDnsResponseWithTtl(domain: String, ttl: Int, rdata: ByteArray): ByteArray {
        val out = mutableListOf<Byte>()
        // Header
        out.add(0x00); out.add(0x01) // Transaction ID
        out.add(0x81.toByte()); out.add(0x80.toByte()) // Flags: response
        out.add(0x00); out.add(0x01) // QDCOUNT: 1
        out.add(0x00); out.add(0x01) // ANCOUNT: 1
        out.add(0x00); out.add(0x00) // NSCOUNT: 0
        out.add(0x00); out.add(0x00) // ARCOUNT: 0
        // Question section
        for (label in domain.split(".")) {
            out.add(label.length.toByte())
            for (ch in label) out.add(ch.code.toByte())
        }
        out.add(0x00)
        out.add(0x00); out.add(0x01) // QTYPE: A
        out.add(0x00); out.add(0x01) // QCLASS: IN
        // Answer section — use compression pointer to question name
        out.add(0xC0.toByte()); out.add(0x0C) // Name: pointer to offset 12
        out.add(0x00); out.add(0x01) // TYPE: A
        out.add(0x00); out.add(0x01) // CLASS: IN
        out.add((ttl shr 24 and 0xFF).toByte())
        out.add((ttl shr 16 and 0xFF).toByte())
        out.add((ttl shr 8 and 0xFF).toByte())
        out.add((ttl and 0xFF).toByte())
        out.add((rdata.size shr 8 and 0xFF).toByte())
        out.add((rdata.size and 0xFF).toByte())
        for (b in rdata) out.add(b)
        return out.toByteArray()
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
