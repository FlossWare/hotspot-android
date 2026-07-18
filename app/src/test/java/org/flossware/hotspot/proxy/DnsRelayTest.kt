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
    private var relayInitialized = false
    private var relayPort = 0
    private var upstreamPort = 0
    private var upstreamServer: DatagramSocket? = null

    @Before
    fun setUp() {
        relayPort = findFreePort()
        upstreamPort = findFreePort()
        relayInitialized = false
    }

    @After
    fun tearDown() {
        if (relayInitialized) relay.stop()
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
        relayInitialized = true
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
    fun `extractQuestionKey returns different keys for different domains`() {
        relay = createRelay()
        val query1 = buildDnsQuery(0x00, 0x01, "alpha.com")
        val query2 = buildDnsQuery(0x00, 0x01, "beta.com")
        val key1 = relay.extractQuestionKey(query1)
        val key2 = relay.extractQuestionKey(query2)
        assertTrue("Keys for different domains should differ", key1 != key2)
    }

    @Test
    fun `extractQuestionKey returns null for exactly 12 bytes with zero qdcount`() {
        relay = createRelay()
        val data = ByteArray(12) // all zeros, qdcount = 0
        assertEquals(null, relay.extractQuestionKey(data))
    }

    @Test
    fun `extractQuestionKey handles single-label domain`() {
        relay = createRelay()
        val query = buildDnsQuery(0x00, 0x01, "localhost")
        val key = relay.extractQuestionKey(query)
        assertTrue("Should extract a key for single-label domain", key != null)
    }

    @Test
    fun `extractQuestionKey handles deeply nested domain`() {
        relay = createRelay()
        val query = buildDnsQuery(0x00, 0x01, "a.b.c.d.e.f.example.com")
        val key = relay.extractQuestionKey(query)
        assertTrue("Should extract a key for deeply nested domain", key != null)
    }

    @Test
    fun `extractMinTtl returns minimum of multiple answer records`() {
        relay = createRelay()
        val response = buildDnsResponseWithMultipleTtls("example.com", listOf(600, 120, 300))
        val ttl = relay.extractMinTtl(response)
        assertEquals(120, ttl)
    }

    @Test
    fun `extractMinTtl with zero TTL returns default`() {
        relay = createRelay()
        // TTL of 0 is not in range 1..minTtl so minTtl stays at MAX_VALUE -> default
        val response = buildDnsResponseWithTtl("example.com", 0, byteArrayOf(1, 2, 3, 4))
        val ttl = relay.extractMinTtl(response)
        assertEquals(DnsRelay.DEFAULT_TTL, ttl)
    }

    @Test
    fun `extractMinTtl with TTL exactly at MIN_TTL`() {
        relay = createRelay()
        val response = buildDnsResponseWithTtl("example.com", DnsRelay.MIN_TTL, byteArrayOf(1, 2, 3, 4))
        val ttl = relay.extractMinTtl(response)
        assertEquals(DnsRelay.MIN_TTL, ttl)
    }

    @Test
    fun `extractMinTtl with TTL exactly at MAX_TTL`() {
        relay = createRelay()
        val response = buildDnsResponseWithTtl("example.com", DnsRelay.MAX_TTL, byteArrayOf(1, 2, 3, 4))
        val ttl = relay.extractMinTtl(response)
        assertEquals(DnsRelay.MAX_TTL, ttl)
    }

    @Test
    fun `patchTransactionId verified through cache hit with different txn ID`() {
        // When a cached response is served, its transaction ID should be patched
        // to match the new query's transaction ID
        upstreamServer = createMockDnsServerWithTtlResponse(upstreamPort)
        relay = createRelay()
        relay.start()
        Thread.sleep(500)

        val query1 = buildDnsQuery(0xAA, 0xBB, "patch-test.com")
        val client = DatagramSocket()
        client.soTimeout = 5000

        // First query with txn ID AA BB
        client.send(DatagramPacket(query1, query1.size, InetAddress.getLoopbackAddress(), relayPort))
        val resp1 = ByteArray(4096)
        val resp1Packet = DatagramPacket(resp1, resp1.size)
        client.receive(resp1Packet)

        Thread.sleep(200)

        // Second query with different txn ID CC DD (should be cache hit)
        val query2 = buildDnsQuery(0xCC, 0xDD, "patch-test.com")
        client.send(DatagramPacket(query2, query2.size, InetAddress.getLoopbackAddress(), relayPort))
        val resp2 = ByteArray(4096)
        val resp2Packet = DatagramPacket(resp2, resp2.size)
        client.receive(resp2Packet)

        // The response transaction ID should match the second query's ID
        assertEquals("Transaction ID high byte should be patched", 0xCC.toByte(), resp2[0])
        assertEquals("Transaction ID low byte should be patched", 0xDD.toByte(), resp2[1])

        assertTrue("Should have 1 cache hit", relay.cacheHits >= 1L)

        client.close()
    }

    @Test
    fun `forwardQuery handles upstream timeout gracefully`() {
        // Don't start an upstream server -- upstream socket will time out
        relay = createRelay()
        relay.start()
        Thread.sleep(500)

        // Send a query that will fail to get an upstream response
        val query = buildDnsQuery(0x00, 0x01, "timeout-test.com")
        val client = DatagramSocket()
        client.soTimeout = 2000

        client.send(DatagramPacket(query, query.size, InetAddress.getLoopbackAddress(), relayPort))

        // Should not get a response (upstream timed out)
        try {
            val resp = ByteArray(4096)
            client.receive(DatagramPacket(resp, resp.size))
            // If we got here, relay forwarded something unexpectedly -- that's also OK
        } catch (_: java.net.SocketTimeoutException) {
            // Expected: no response because upstream is not available
        }
        client.close()

        // Relay should still be running
        assertTrue(relay.isRunning)
    }

    @Test
    fun `CachedDnsResponse equality and hashCode`() {
        val data1 = byteArrayOf(0x01, 0x02, 0x03)
        val data2 = byteArrayOf(0x01, 0x02, 0x03)
        val data3 = byteArrayOf(0x04, 0x05, 0x06)

        val r1 = DnsRelay.CachedDnsResponse(data1, 1000L)
        val r2 = DnsRelay.CachedDnsResponse(data2, 1000L)
        val r3 = DnsRelay.CachedDnsResponse(data3, 1000L)
        val r4 = DnsRelay.CachedDnsResponse(data1, 2000L)

        assertEquals(r1, r2)
        assertEquals(r1.hashCode(), r2.hashCode())
        assertFalse(r1 == r3)
        assertFalse(r1 == r4)
    }

    @Test
    fun `CachedDnsResponse isExpired returns true for past time`() {
        val response = DnsRelay.CachedDnsResponse(byteArrayOf(0x01), System.currentTimeMillis() - 1000)
        assertTrue(response.isExpired())
    }

    @Test
    fun `CachedDnsResponse isExpired returns false for future time`() {
        val response = DnsRelay.CachedDnsResponse(byteArrayOf(0x01), System.currentTimeMillis() + 60_000)
        assertFalse(response.isExpired())
    }

    @Test
    fun `ByteArrayKey equality and hashCode`() {
        val k1 = DnsRelay.ByteArrayKey(byteArrayOf(0x01, 0x02))
        val k2 = DnsRelay.ByteArrayKey(byteArrayOf(0x01, 0x02))
        val k3 = DnsRelay.ByteArrayKey(byteArrayOf(0x03, 0x04))

        assertEquals(k1, k2)
        assertEquals(k1.hashCode(), k2.hashCode())
        assertFalse(k1 == k3)
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

    private fun buildDnsResponseWithMultipleTtls(domain: String, ttls: List<Int>): ByteArray {
        val out = mutableListOf<Byte>()
        // Header
        out.add(0x00); out.add(0x01) // Transaction ID
        out.add(0x81.toByte()); out.add(0x80.toByte()) // Flags: response
        out.add(0x00); out.add(0x01) // QDCOUNT: 1
        out.add((ttls.size shr 8 and 0xFF).toByte())
        out.add((ttls.size and 0xFF).toByte()) // ANCOUNT
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
        // Answer sections
        for ((index, ttl) in ttls.withIndex()) {
            out.add(0xC0.toByte()); out.add(0x0C) // Name: pointer to offset 12
            out.add(0x00); out.add(0x01) // TYPE: A
            out.add(0x00); out.add(0x01) // CLASS: IN
            out.add((ttl shr 24 and 0xFF).toByte())
            out.add((ttl shr 16 and 0xFF).toByte())
            out.add((ttl shr 8 and 0xFF).toByte())
            out.add((ttl and 0xFF).toByte())
            val rdata = byteArrayOf(10, 0, 0, (index + 1).toByte())
            out.add((rdata.size shr 8 and 0xFF).toByte())
            out.add((rdata.size and 0xFF).toByte())
            for (b in rdata) out.add(b)
        }
        return out.toByteArray()
    }

    private fun createMockDnsServerWithTtlResponse(port: Int): DatagramSocket {
        val server = DatagramSocket(port, InetAddress.getLoopbackAddress())
        Thread {
            val buffer = ByteArray(4096)
            while (!server.isClosed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    server.soTimeout = 1000
                    server.receive(packet)
                    // Build a response with a cacheable TTL
                    val mockResponse = buildDnsResponseWithTtl("example.com", 300, byteArrayOf(1, 2, 3, 4))
                    // Patch transaction ID from query
                    mockResponse[0] = packet.data[0]
                    mockResponse[1] = packet.data[1]
                    val response = DatagramPacket(
                        mockResponse, mockResponse.size,
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

    private fun createRelay(): DnsRelay {
        relayInitialized = true
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
