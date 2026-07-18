package org.flossware.hotspot.proxy

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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger
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
) {
    @Volatile private var serverSocket: ServerSocket? = null
    private val executor = ThreadPoolExecutor(
        4, 32, 60L, TimeUnit.SECONDS, LinkedBlockingQueue(64), ThreadPoolExecutor.CallerRunsPolicy(),
    )
    private val running = AtomicBoolean(false)
    private val _bytesTransferred = AtomicLong(0)
    val bytesTransferred: Long get() = _bytesTransferred.get()

    private val requireAuth: Boolean get() = username != null && password != null

    // Connection tracking (#16)
    private val connectionsPerClient = ConcurrentHashMap<InetAddress, AtomicInteger>()
    private val totalActiveConnections = AtomicInteger(0)
    val activeConnections: Int get() = totalActiveConnections.get()

    fun start() {
        if (running.getAndSet(true)) return
        executor.execute {
            var ss: ServerSocket? = null
            try {
                ss = ServerSocket(port, 50, bindAddress)
                ss.soTimeout = 0
                serverSocket = ss
                log.info("SOCKS5 listening on $bindAddress:$port" +
                    if (requireAuth) " (auth required)" else " (no auth)")
                while (running.get()) {
                    try {
                        val client = ss.accept()
                        val clientAddr = client.inetAddress

                        // Check total connection limit
                        if (totalActiveConnections.get() >= maxTotalConnections) {
                            log.warning("Total connection limit reached ($maxTotalConnections), rejecting ${clientAddr.hostAddress}")
                            client.closeSilently()
                            continue
                        }

                        // Check per-client connection limit
                        val clientCount = connectionsPerClient
                            .computeIfAbsent(clientAddr) { AtomicInteger(0) }
                        if (clientCount.get() >= maxConnectionsPerClient) {
                            log.warning("Per-client limit reached ($maxConnectionsPerClient) for ${clientAddr.hostAddress}")
                            client.closeSilently()
                            continue
                        }

                        clientCount.incrementAndGet()
                        totalActiveConnections.incrementAndGet()
                        log.fine("Connection accepted from ${clientAddr.hostAddress}" +
                            " (client: ${clientCount.get()}, total: ${totalActiveConnections.get()})")

                        executor.execute {
                            try {
                                handleClient(client)
                            } finally {
                                releaseConnection(clientAddr)
                            }
                        }
                    } catch (_: SocketException) {
                        break
                    }
                }
            } catch (e: IOException) {
                log.log(Level.SEVERE, "SOCKS5 server error", e)
            } finally {
                ss?.close()
            }
        }
    }

    fun stop() {
        running.set(false)
        serverSocket?.close()
        serverSocket = null
        executor.shutdownNow()
        connectionsPerClient.clear()
        totalActiveConnections.set(0)
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
        val clientAddr = client.inetAddress?.hostAddress ?: "unknown"
        try {
            client.soTimeout = SOCKET_TIMEOUT_MS
            val input = client.getInputStream()
            val output = client.getOutputStream()

            if (!negotiate(input, output)) {
                log.fine("Negotiation failed for $clientAddr")
                return
            }

            val version = input.read()
            if (version == -1) {
                log.fine("Connection closed during request from $clientAddr")
                return
            }
            if (version != VERSION.toInt() and 0xFF) {
                log.fine("Invalid request version 0x${version.toString(16)} from $clientAddr")
                return
            }

            val cmd = input.read()
            if (cmd == -1) {
                log.fine("Connection closed reading command from $clientAddr")
                return
            }
            val reserved = input.read() // reserved
            if (reserved == -1) {
                log.fine("Connection closed reading reserved byte from $clientAddr")
                return
            }

            val (host, port) = try {
                readAddress(input)
            } catch (e: IOException) {
                log.fine("Failed to read address from $clientAddr: ${e.message}")
                sendReply(output, REPLY_ADDR_NOT_SUPPORTED)
                return
            }

            when (cmd) {
                CMD_CONNECT.toInt() and 0xFF -> {
                    log.info("CONNECT from $clientAddr to $host:$port")
                    handleConnect(client, input, output, host, port)
                }
                else -> {
                    log.fine("Unsupported command 0x${cmd.toString(16)} from $clientAddr")
                    sendReply(output, REPLY_CMD_NOT_SUPPORTED)
                }
            }
        } catch (e: SocketTimeoutException) {
            log.fine("Timeout handling client $clientAddr: ${e.message}")
        } catch (e: IOException) {
            log.fine("Client handler error for $clientAddr: ${e.message}")
        } finally {
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
            // Require username/password auth (RFC 1929)
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

        // No auth required — accept NO_AUTH
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

    /**
     * RFC 1929 username/password subnegotiation.
     *
     * Client sends: VER(1) ULEN(1) UNAME(1..255) PLEN(1) PASSWD(1..255)
     * Server replies: VER(1) STATUS(1) where STATUS 0x00 = success
     */
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

        // Constant-time comparison to prevent timing attacks
        val userMatch = constantTimeEquals(clientUser, username ?: "")
        val passMatch = constantTimeEquals(clientPass, password ?: "")

        return if (userMatch && passMatch) {
            log.fine("Authentication succeeded for user '$clientUser'")
            output.write(byteArrayOf(AUTH_VERSION, AUTH_SUCCESS))
            output.flush()
            true
        } else {
            log.warning("Authentication failed for user '$clientUser'")
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
    ) {
        val clientAddr = client.inetAddress?.hostAddress ?: "unknown"
        val factory = socketFactoryProvider() ?: run {
            log.fine("No socket factory available for $clientAddr -> $host:$port")
            sendReply(output, REPLY_GENERAL_FAILURE)
            return
        }

        val resolved = try {
            dnsResolver(host)
        } catch (e: Exception) {
            log.fine("DNS resolution failed for $host: ${e.message}")
            sendReply(output, REPLY_HOST_UNREACHABLE)
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
            log.fine("Connection refused to $host:$port ($resolved): ${e.message}")
            sendReply(output, REPLY_CONNECTION_REFUSED)
            return
        }

        try {
            val localAddr = upstream.localAddress
            val localPort = upstream.localPort
            sendReply(output, REPLY_SUCCESS, localAddr, localPort)

            val clientToServer = Thread {
                try {
                    relay(input, upstream.getOutputStream())
                } finally {
                    upstream.closeSilently()
                }
            }
            val serverToClient = Thread {
                try {
                    relay(upstream.getInputStream(), client.getOutputStream())
                } finally {
                    client.closeSilently()
                }
            }
            clientToServer.start()
            serverToClient.start()
            clientToServer.join()
            serverToClient.join()
            log.fine("Connection closed: $clientAddr -> $host:$port" +
                " (${_bytesTransferred.get()} total bytes)")
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
            log.fine("Cached HTTP connection refused to $host:80: ${e.message}")
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

    internal fun relay(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        try {
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                output.write(buffer, 0, count)
                output.flush()
                _bytesTransferred.addAndGet(count.toLong())
            }
        } catch (_: IOException) {
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
        try { close() } catch (_: IOException) { }
    }

    companion object {
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
        private val log = Logger.getLogger(Socks5Server::class.java.name)

        /**
         * Constant-time string comparison to prevent timing attacks on credentials.
         */
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
