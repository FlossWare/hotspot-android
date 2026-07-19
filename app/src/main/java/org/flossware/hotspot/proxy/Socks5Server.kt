package org.flossware.hotspot.proxy

import timber.log.Timber
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
                Timber.tag(TAG).i("socks5_start event=socks5_listen port=$port auth=${requireAuth}")
                while (running.get()) {
                    try {
                        val client = ss.accept()
                        val clientAddr = client.inetAddress

                        if (totalActiveConnections.get() >= maxTotalConnections) {
                            logConnectionLimit(clientAddr)
                            client.closeSilently()
                            continue
                        }

                        val clientCount = connectionsPerClient
                            .computeIfAbsent(clientAddr) { AtomicInteger(0) }
                        if (clientCount.get() >= maxConnectionsPerClient) {
                            logClientLimit(clientAddr)
                            client.closeSilently()
                            continue
                        }

                        clientCount.incrementAndGet()
                        totalActiveConnections.incrementAndGet()
                        activeSockets.add(client)
                        if (debugMode) logConnectionAccepted(clientAddr, clientCount)

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
                Timber.tag(TAG).e(e, "SOCKS5 server error")
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
                Timber.tag(TAG).w("Executor did not terminate within %dms", SHUTDOWN_TIMEOUT_MS)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        connectionsPerClient.clear()
        totalActiveConnections.set(0)
        Timber.tag(TAG).i("socks5_stop event=socks5_server_stopped")
    }

    val isRunning: Boolean get() = running.get()

    private fun logConnectionLimit(clientAddr: InetAddress) {
        Timber.tag(TAG).w(
            "Total connection limit reached (%d), rejecting %s",
            maxTotalConnections, clientAddr.hostAddress,
        )
    }

    private fun logClientLimit(clientAddr: InetAddress) {
        Timber.tag(TAG).w(
            "Per-client limit reached (%d) for %s",
            maxConnectionsPerClient, clientAddr.hostAddress,
        )
    }

    private fun logConnectionAccepted(clientAddr: InetAddress, clientCount: AtomicInteger) {
        Timber.tag(TAG).d(
            "socks5_connection_open client=%s count=%d total=%d",
            clientAddr.hostAddress, clientCount.get(),
            totalActiveConnections.get(),
        )
    }

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
                if (debugMode) Timber.tag(TAG).d("Negotiation failed for %s", clientAddr)
                return
            }

            val version = input.read()
            if (version == -1) {
                if (debugMode) Timber.tag(TAG).d("Connection closed during request from %s", clientAddr)
                return
            }
            if (version != VERSION.toInt() and 0xFF) {
                Timber.tag(TAG).w("Invalid SOCKS version from %s: %d", clientAddr, version)
                return
            }

            val cmd = input.read()
            if (cmd == -1) {
                if (debugMode) Timber.tag(TAG).d("Connection closed reading command from %s", clientAddr)
                return
            }
            val reserved = input.read()
            if (reserved == -1) {
                if (debugMode) Timber.tag(TAG).d("Connection closed reading reserved byte from %s", clientAddr)
                return
            }

            val (host, port) = try {
                readAddress(input)
            } catch (e: IOException) {
                if (debugMode) Timber.tag(TAG).d("Failed to read address from %s: %s", clientAddr, e.message)
                sendReply(output, REPLY_ADDR_NOT_SUPPORTED)
                return
            }

            when (cmd) {
                CMD_CONNECT.toInt() and 0xFF -> {
                    if (debugMode) Timber.tag(TAG).d("CONNECT %s:%d from %s", host, port, clientAddr)
                    handleConnect(client, input, output, host, port, connectionBytes)
                }
                else -> {
                    Timber.tag(TAG).w("Unsupported SOCKS command %d from %s", cmd, clientAddr)
                    sendReply(output, REPLY_CMD_NOT_SUPPORTED)
                }
            }
        } catch (e: SocketTimeoutException) {
            if (debugMode) Timber.tag(TAG).d("Timeout handling client %s: %s", clientAddr, e.message)
        } catch (e: IOException) {
            Timber.tag(TAG).d("Client handler error for %s: %s", clientAddr, e.message)
        } finally {
            val duration = System.currentTimeMillis() - startTime
            if (debugMode) {
                Timber.tag(TAG).d("socks5_connection_close event=connection_closed client=%s bytes=%d duration_ms=%d",
                    clientAddr, connectionBytes.get(), duration)
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
            if (debugMode) Timber.tag(TAG).d("Authentication succeeded")
            output.write(byteArrayOf(AUTH_VERSION, AUTH_SUCCESS))
            output.flush()
            true
        } else {
            Timber.tag(TAG).w("Authentication failed")
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
            Timber.tag(TAG).w("No socket factory available for CONNECT to %s:%d", host, port)
            sendReply(output, REPLY_GENERAL_FAILURE)
            return
        }

        val resolved = try {
            dnsResolver(host)
        } catch (e: Exception) {
            Timber.tag(TAG).w("DNS resolution failed for %s: %s", host, e.message)
            sendReply(output, REPLY_HOST_UNREACHABLE)
            return
        }

        if (ssrfProtection && isBlockedDestination(resolved)) {
            Timber.tag(TAG).w("SSRF blocked: CONNECT to %s:%d from %s", host, port, clientAddr)
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
            Timber.tag(TAG).w("Connection refused to %s:%d: %s", host, port, e.message)
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
                Timber.tag(TAG).d("socks5_connection_close event=relay_closed client=%s host=%s port=%d bytes=%d",
                    clientAddr, host, port, connectionBytes.get())
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
            Timber.tag(TAG).w("Connection refused to %s:80: %s", host, e.message)
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
                if (debugMode) Timber.tag(TAG).d("Served from cache: %s %s", host, requestLine)
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
            if (debugMode) Timber.tag(TAG).d("Relay stream closed: %s", e.message)
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
            if (debugMode) Timber.tag(TAG).d("Socket close: %s", e.message)
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
         * Returns true if the given address is a loopback or link-local address
         * that should be blocked to prevent SSRF attacks.
         */
        fun isBlockedDestination(addr: InetAddress): Boolean {
            return addr.isLoopbackAddress || addr.isLinkLocalAddress
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
