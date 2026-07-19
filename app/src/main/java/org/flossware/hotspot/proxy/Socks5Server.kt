package org.flossware.hotspot.proxy

import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory

class Socks5Server(
    private val bindAddress: InetAddress,
    private val port: Int = 1080,
    private val socketFactoryProvider: () -> SocketFactory?,
    private val dnsResolver: (String) -> InetAddress = { InetAddress.getByName(it) },
    private val httpCache: HttpCache? = null,
    private val username: String? = null,
    private val password: String? = null,
    private val maxConnectionsPerClient: Int = 10,
    private val maxTotalConnections: Int = 100,
    private val ssrfProtection: Boolean = true,
    @Volatile var debugMode: Boolean = false,
) {
    @Volatile private var serverSocket: ServerSocket? = null
    private val executor = ThreadPoolExecutor(
        4, 32, 60L, TimeUnit.SECONDS, LinkedBlockingQueue(64), ThreadPoolExecutor.CallerRunsPolicy(),
    )
    private val running = AtomicBoolean(false)
    private val _bytesTransferred = AtomicLong(0)
    val bytesTransferred: Long get() = _bytesTransferred.get()

    private val requireAuth: Boolean get() = username != null && password != null

    private val connectionsPerClient = ConcurrentHashMap<InetAddress, AtomicInteger>()
    private val totalActiveConnections = AtomicInteger(0)
    val activeConnections: Int get() = totalActiveConnections.get()
    private val activeSockets = CopyOnWriteArrayList<Socket>()

    fun start() {
        if (running.getAndSet(true)) return
        executor.execute {
            var ss: ServerSocket? = null
            try {
                ss = ServerSocket(port, 50, bindAddress)
                ss.soTimeout = 0
                serverSocket = ss
                Log.i(TAG, "SOCKS5 listening on $bindAddress:$port" +
                    if (requireAuth) " (auth required)" else " (no auth)")
                while (running.get()) {
                    try {
                        val client = ss.accept()
                        val clientAddr = client.inetAddress

                        if (totalActiveConnections.get() >= maxTotalConnections) {
                            Log.w(TAG, "Total connection limit reached ($maxTotalConnections), rejecting ${clientAddr.hostAddress}")
                            client.closeSilently()
                            continue
                        }

                        val clientCount = connectionsPerClient
                            .computeIfAbsent(clientAddr) { AtomicInteger(0) }
                        if (clientCount.get() >= maxConnectionsPerClient) {
                            Log.w(TAG, "Per-client limit reached ($maxConnectionsPerClient) for ${clientAddr.hostAddress}")
                            client.closeSilently()
                            continue
                        }

                        clientCount.incrementAndGet()
                        totalActiveConnections.incrementAndGet()
                        activeSockets.add(client)
                        if (debugMode) {
                            Log.d(TAG, "Connection accepted from ${clientAddr.hostAddress}" +
                                " (client: ${clientCount.get()}, total: ${totalActiveConnections.get()})")
                        }

                        executor.execute {
                            try {
                                handleClient(client)
                            } finally {
                                activeSockets.remove(client)
                                releaseConnection(clientAddr)
                            }
                        }
                    } catch (_: SocketException) {
                        break
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "SOCKS5 server error", e)
            } finally {
                ss?.close()
            }
        }
    }

    fun stop() {
        running.set(false)
        serverSocket?.close()
        serverSocket = null
        for (sock in activeSockets) {
            sock.closeSilently()
        }
        activeSockets.clear()
        executor.shutdownNow()
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Executor did not terminate within ${SHUTDOWN_TIMEOUT_MS}ms")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        connectionsPerClient.clear()
        totalActiveConnections.set(0)
        Log.i(TAG, "SOCKS5 server stopped")
    }

    val isRunning: Boolean get() = running.get()

    private fun releaseConnection(clientAddr: InetAddress) {
        totalActiveConnections.decrementAndGet()
        connectionsPerClient.computeIfPresent(clientAddr) { _, count ->
            val remaining = count.decrementAndGet()
            if (remaining <= 0) null else count
        }
    }

    internal fun handleClient(client: Socket) {
        val startTime = System.currentTimeMillis()
        val clientAddr = client.inetAddress?.hostAddress ?: "unknown"
        val connectionBytes = AtomicLong(0)
        try {
            client.soTimeout = SOCKET_TIMEOUT_MS
            val input = client.getInputStream()
            val output = client.getOutputStream()

            if (!negotiate(input, output)) {
                if (debugMode) Log.d(TAG, "Negotiation failed for $clientAddr")
                return
            }

            val version = input.read()
            if (version == -1) {
                if (debugMode) Log.d(TAG, "Connection closed during request from $clientAddr")
                return
            }
            if (version != VERSION.toInt() and 0xFF) {
                Log.w(TAG, "Invalid SOCKS version from $clientAddr: $version")
                return
            }

            val cmd = input.read()
            if (cmd == -1) {
                if (debugMode) Log.d(TAG, "Connection closed reading command from $clientAddr")
                return
            }
            val reserved = input.read()
            if (reserved == -1) {
                if (debugMode) Log.d(TAG, "Connection closed reading reserved byte from $clientAddr")
                return
            }

            val (host, port) = try {
                readAddress(input)
            } catch (e: IOException) {
                if (debugMode) Log.d(TAG, "Failed to read address from $clientAddr: ${e.message}")
                sendReply(output, REPLY_ADDR_NOT_SUPPORTED)
                return
            }

            when (cmd) {
                CMD_CONNECT.toInt() and 0xFF -> {
                    if (debugMode) Log.d(TAG, "CONNECT $host:$port from $clientAddr")
                    handleConnect(client, input, output, host, port, connectionBytes)
                }
                else -> {
                    Log.w(TAG, "Unsupported SOCKS command $cmd from $clientAddr")
                    sendReply(output, REPLY_CMD_NOT_SUPPORTED)
                }
            }
        } catch (e: SocketTimeoutException) {
            if (debugMode) Log.d(TAG, "Timeout handling client $clientAddr: ${e.message}")
        } catch (e: IOException) {
            Log.d(TAG, "Client handler error for $clientAddr: ${e.message}")
        } finally {
            val duration = System.currentTimeMillis() - startTime
            if (debugMode) {
                Log.d(TAG, "Connection closed for $clientAddr: ${connectionBytes.get()}B transferred in ${duration}ms")
            }
            client.closeSilently()
        }
    }

    internal fun negotiate(input: InputStream, output: OutputStream): Boolean {
        val version = input.read()
        if (version == -1) return false
        if (version != VERSION.toInt() and 0xFF) {
            output.write(byteArrayOf(VERSION, AUTH_NO_ACCEPTABLE))
            output.flush()
            return false
        }

        val nMethods = input.read()
        if (nMethods <= 0) {
            output.write(byteArrayOf(VERSION, AUTH_NO_ACCEPTABLE))
            output.flush()
            return false
        }

        val methods = ByteArray(nMethods)
        var read = 0
        while (read < nMethods) {
            val n = input.read(methods, read, nMethods - read)
            if (n == -1) return false
            read += n
        }

        if (requireAuth) {
            val hasUserPassAuth = methods.any { it == AUTH_USERNAME_PASSWORD }
            if (!hasUserPassAuth) {
                output.write(byteArrayOf(VERSION, AUTH_NO_ACCEPTABLE))
                output.flush()
                return false
            }
            output.write(byteArrayOf(VERSION, AUTH_USERNAME_PASSWORD))
            output.flush()
            return authenticateUsernamePassword(input, output)
        }

        val hasNoAuth = methods.any { it == AUTH_NONE }
        if (!hasNoAuth) {
            output.write(byteArrayOf(VERSION, AUTH_NO_ACCEPTABLE))
            output.flush()
            return false
        }

        output.write(byteArrayOf(VERSION, AUTH_NONE))
        output.flush()
        return true
    }

    internal fun authenticateUsernamePassword(input: InputStream, output: OutputStream): Boolean {
        val authVersion = input.read()
        if (authVersion == -1) return false
        if (authVersion != AUTH_VERSION.toInt() and 0xFF) {
            output.write(byteArrayOf(AUTH_VERSION, AUTH_FAILURE))
            output.flush()
            return false
        }

        val ulen = input.read()
        if (ulen <= 0 || ulen > 255) {
            output.write(byteArrayOf(AUTH_VERSION, AUTH_FAILURE))
            output.flush()
            return false
        }
        val uname = ByteArray(ulen)
        readFully(input, uname)

        val plen = input.read()
        if (plen <= 0 || plen > 255) {
            output.write(byteArrayOf(AUTH_VERSION, AUTH_FAILURE))
            output.flush()
            return false
        }
        val passwd = ByteArray(plen)
        readFully(input, passwd)

        val clientUser = String(uname, Charsets.UTF_8)
        val clientPass = String(passwd, Charsets.UTF_8)

        val userMatch = constantTimeEquals(clientUser, username ?: "")
        val passMatch = constantTimeEquals(clientPass, password ?: "")

        return if (userMatch && passMatch) {
            if (debugMode) Log.d(TAG, "Authentication succeeded for user '$clientUser'")
            output.write(byteArrayOf(AUTH_VERSION, AUTH_SUCCESS))
            output.flush()
            true
        } else {
            Log.w(TAG, "Authentication failed for user '$clientUser'")
            output.write(byteArrayOf(AUTH_VERSION, AUTH_FAILURE))
            output.flush()
            false
        }
    }

    internal fun readAddress(input: InputStream): Pair<String, Int> {
        val addrType = input.read()
        if (addrType == -1) throw IOException("Connection closed reading address type")

        val host = when (addrType) {
            ADDR_IPV4.toInt() and 0xFF -> {
                val addr = ByteArray(4)
                readFully(input, addr)
                InetAddress.getByAddress(addr).hostAddress ?: "0.0.0.0"
            }
            ADDR_DOMAIN.toInt() and 0xFF -> {
                val len = input.read()
                if (len <= 0) throw IOException("Invalid domain name length: $len")
                if (len > 253) throw IOException("Domain name too long: $len")
                val domain = ByteArray(len)
                readFully(input, domain)
                String(domain, Charsets.US_ASCII)
            }
            ADDR_IPV6.toInt() and 0xFF -> {
                val addr = ByteArray(16)
                readFully(input, addr)
                InetAddress.getByAddress(addr).hostAddress ?: "::0"
            }
            else -> throw IOException("Unsupported address type: 0x${addrType.toString(16)}")
        }

        val portHigh = input.read()
        val portLow = input.read()
        if (portHigh == -1 || portLow == -1) throw IOException("Connection closed reading port")
        val port = (portHigh shl 8) or portLow
        if (port < 0 || port > 65535) throw IOException("Invalid port number: $port")

        return host to port
    }

    internal fun handleConnect(
        client: Socket,
        input: InputStream,
        output: OutputStream,
        host: String,
        port: Int,
        connectionBytes: AtomicLong = AtomicLong(0),
    ) {
        val clientAddr = client.inetAddress?.hostAddress ?: "unknown"
        val factory = socketFactoryProvider() ?: run {
            Log.w(TAG, "No socket factory available for CONNECT to $host:$port")
            sendReply(output, REPLY_GENERAL_FAILURE)
            return
        }

        val resolved = try {
            dnsResolver(host)
        } catch (e: Exception) {
            Log.w(TAG, "DNS resolution failed for $host: ${e.message}")
            sendReply(output, REPLY_HOST_UNREACHABLE)
            return
        }

        if (ssrfProtection && isBlockedDestination(resolved)) {
            Log.w(TAG, "SSRF blocked: CONNECT to $host:$port ($resolved) from $clientAddr")
            sendReply(output, REPLY_NOT_ALLOWED)
            return
        }

        if (httpCache != null && port == 80) {
            handleCachedHttpConnect(client, input, output, host, resolved, factory)
            return
        }

        val upstream: Socket
        try {
            upstream = factory.createSocket(resolved, port)
            upstream.soTimeout = SOCKET_TIMEOUT_MS
        } catch (e: IOException) {
            Log.w(TAG, "Connection refused to $host:$port ($resolved): ${e.message}")
            sendReply(output, REPLY_CONNECTION_REFUSED)
            return
        }

        try {
            val localAddr = upstream.localAddress
            val localPort = upstream.localPort
            sendReply(output, REPLY_SUCCESS, localAddr, localPort)

            val clientToServer = Thread {
                try {
                    relay(input, upstream.getOutputStream(), connectionBytes)
                } finally {
                    upstream.closeSilently()
                }
            }
            val serverToClient = Thread {
                try {
                    relay(upstream.getInputStream(), client.getOutputStream(), connectionBytes)
                } finally {
                    client.closeSilently()
                }
            }
            clientToServer.start()
            serverToClient.start()
            clientToServer.join()
            serverToClient.join()
            if (debugMode) {
                Log.d(TAG, "Connection closed: $clientAddr -> $host:$port" +
                    " (${connectionBytes.get()} bytes)")
            }
        } finally {
            upstream.closeSilently()
            client.closeSilently()
        }
    }

    private fun handleCachedHttpConnect(
        client: Socket,
        input: InputStream,
        output: OutputStream,
        host: String,
        resolved: InetAddress,
        factory: SocketFactory,
    ) {
        val cache = httpCache ?: return
        val upstream: Socket
        try {
            upstream = factory.createSocket(resolved, 80)
            upstream.soTimeout = SOCKET_TIMEOUT_MS
        } catch (e: IOException) {
            Log.w(TAG, "Connection refused to $host:80 ($resolved): ${e.message}")
            sendReply(output, REPLY_CONNECTION_REFUSED)
            return
        }

        try {
            sendReply(output, REPLY_SUCCESS, upstream.localAddress, upstream.localPort)

            val bufferedInput = BufferedInputStream(input, 8192)
            val requestLine = HttpCache.readLine(bufferedInput)
            if (requestLine == null || !requestLine.startsWith("GET ")) {
                upstream.getOutputStream().write((requestLine ?: "").toByteArray())
                upstream.getOutputStream().write("\r\n".toByteArray())
                val clientToServer = Thread {
                    relay(bufferedInput, upstream.getOutputStream())
                    upstream.closeSilently()
                }
                val serverToClient = Thread {
                    relay(upstream.getInputStream(), client.getOutputStream())
                    client.closeSilently()
                }
                clientToServer.start()
                serverToClient.start()
                clientToServer.join()
                serverToClient.join()
                return
            }

            val headers = StringBuilder()
            while (true) {
                val line = HttpCache.readLine(bufferedInput) ?: break
                headers.append(line).append("\r\n")
                if (line.isEmpty()) break
            }

            if (cache.tryServeFromCache(host, requestLine, headers.toString(), output)) {
                if (debugMode) Log.d(TAG, "Served from cache: $host $requestLine")
                upstream.closeSilently()
                return
            }

            val upstreamOut = upstream.getOutputStream()
            upstreamOut.write(requestLine.toByteArray())
            upstreamOut.write("\r\n".toByteArray())
            upstreamOut.write(headers.toString().toByteArray())
            upstreamOut.flush()

            cache.cacheResponse(
                host, requestLine, upstream.getInputStream(), output,
                upstreamOut, bufferedInput,
                requestHeaders = headers.toString(),
            )
        } finally {
            upstream.closeSilently()
        }
    }

    internal fun sendReply(
        output: OutputStream,
        status: Byte,
        bindAddr: InetAddress? = null,
        bindPort: Int = 0,
    ) {
        val addr = bindAddr ?: InetAddress.getByName("0.0.0.0")
        val addrBytes = addr.address
        val addrType = when (addr) {
            is Inet6Address -> ADDR_IPV6
            else -> ADDR_IPV4
        }

        val reply = ByteArray(4 + addrBytes.size + 2)
        reply[0] = VERSION
        reply[1] = status
        reply[2] = 0x00 // reserved
        reply[3] = addrType
        System.arraycopy(addrBytes, 0, reply, 4, addrBytes.size)
        reply[reply.size - 2] = ((bindPort shr 8) and 0xFF).toByte()
        reply[reply.size - 1] = (bindPort and 0xFF).toByte()

        output.write(reply)
        output.flush()
    }

    internal fun relay(
        input: InputStream,
        output: OutputStream,
        connectionBytes: AtomicLong? = null,
    ) {
        val buffer = ByteArray(8192)
        try {
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                output.write(buffer, 0, count)
                output.flush()
                _bytesTransferred.addAndGet(count.toLong())
                connectionBytes?.addAndGet(count.toLong())
            }
        } catch (e: IOException) {
            if (debugMode) Log.d(TAG, "Relay stream closed: ${e.message}")
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val n = input.read(buf, offset, buf.size - offset)
            if (n == -1) throw IOException("Unexpected end of stream after $offset of ${buf.size} bytes")
            offset += n
        }
    }

    private fun Socket.closeSilently() {
        try {
            close()
        } catch (e: IOException) {
            if (debugMode) Log.d(TAG, "Socket close: ${e.message}")
        }
    }

    internal companion object {
        private const val TAG = "Socks5Server"
        const val VERSION: Byte = 0x05
        const val AUTH_NONE: Byte = 0x00
        const val AUTH_USERNAME_PASSWORD: Byte = 0x02
        val AUTH_NO_ACCEPTABLE: Byte = 0xFF.toByte()
        const val AUTH_VERSION: Byte = 0x01
        const val AUTH_SUCCESS: Byte = 0x00
        const val AUTH_FAILURE: Byte = 0x01
        const val CMD_CONNECT: Byte = 0x01
        const val ADDR_IPV4: Byte = 0x01
        const val ADDR_DOMAIN: Byte = 0x03
        const val ADDR_IPV6: Byte = 0x04
        const val REPLY_SUCCESS: Byte = 0x00
        const val REPLY_GENERAL_FAILURE: Byte = 0x01
        const val REPLY_NOT_ALLOWED: Byte = 0x02
        const val REPLY_NETWORK_UNREACHABLE: Byte = 0x03
        const val REPLY_HOST_UNREACHABLE: Byte = 0x04
        const val REPLY_CONNECTION_REFUSED: Byte = 0x05
        const val REPLY_CMD_NOT_SUPPORTED: Byte = 0x07
        const val REPLY_ADDR_NOT_SUPPORTED: Byte = 0x08
        const val SOCKET_TIMEOUT_MS = 60_000
        const val SHUTDOWN_TIMEOUT_MS = 3000L

        /**
         * Returns true if the given address should be blocked to prevent SSRF attacks.
         *
         * Blocks:
         * - IPv4/IPv6 loopback (127.0.0.0/8, ::1)
         * - IPv4/IPv6 link-local (169.254.0.0/16, fe80::/10)
         * - IPv6 site-local (fec0::/10, deprecated RFC 3879)
         * - IPv4-mapped IPv6 addresses (::ffff:x.x.x.x) where the mapped
         *   IPv4 address is itself blocked
         */
        fun isBlockedDestination(addr: InetAddress): Boolean {
            if (addr.isLoopbackAddress || addr.isLinkLocalAddress) return true
            if (addr is Inet6Address) {
                // Block deprecated IPv6 site-local (fec0::/10)
                if (addr.isSiteLocalAddress) return true
                // Check IPv4-mapped IPv6 addresses (::ffff:x.x.x.x)
                // to prevent SSRF bypass via IPv6-encoded IPv4 targets
                val bytes = addr.address
                if (isIpv4Mapped(bytes)) {
                    val mapped = InetAddress.getByAddress(bytes.copyOfRange(12, 16))
                    if (mapped.isLoopbackAddress || mapped.isLinkLocalAddress) return true
                }
            }
            return false
        }

        /**
         * Returns true if the 16-byte IPv6 address is an IPv4-mapped address
         * (::ffff:x.x.x.x, bytes 0-9 are 0, bytes 10-11 are 0xFF).
         */
        internal fun isIpv4Mapped(addr: ByteArray): Boolean {
            if (addr.size != 16) return false
            for (i in 0 until 10) {
                if (addr[i] != 0.toByte()) return false
            }
            return addr[10] == 0xFF.toByte() && addr[11] == 0xFF.toByte()
        }

        internal fun constantTimeEquals(a: String, b: String): Boolean {
            val aBytes = a.toByteArray(Charsets.UTF_8)
            val bBytes = b.toByteArray(Charsets.UTF_8)
            if (aBytes.size != bBytes.size) return false
            var result = 0
            for (i in aBytes.indices) {
                result = result or (aBytes[i].toInt() xor bBytes[i].toInt())
            }
            return result == 0
        }
    }
}
