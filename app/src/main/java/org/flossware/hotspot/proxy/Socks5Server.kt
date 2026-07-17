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
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
) {
    @Volatile private var serverSocket: ServerSocket? = null
    private val executor = ThreadPoolExecutor(
        4, 32, 60L, TimeUnit.SECONDS, SynchronousQueue(), ThreadPoolExecutor.CallerRunsPolicy(),
    )
    private val running = AtomicBoolean(false)
    private val _bytesTransferred = AtomicLong(0)
    val bytesTransferred: Long get() = _bytesTransferred.get()

    fun start() {
        if (running.getAndSet(true)) return
        executor.execute {
            var ss: ServerSocket? = null
            try {
                ss = ServerSocket(port, 50, bindAddress)
                ss.soTimeout = 0
                serverSocket = ss
                log.info("SOCKS5 listening on $bindAddress:$port")
                while (running.get()) {
                    try {
                        val client = ss.accept()
                        executor.execute { handleClient(client) }
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
    }

    val isRunning: Boolean get() = running.get()

    internal fun handleClient(client: Socket) {
        try {
            client.soTimeout = 60_000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            if (!negotiate(input, output)) return

            val version = input.read()
            if (version != VERSION.toInt() and 0xFF) return

            val cmd = input.read()
            input.read() // reserved

            val (host, port) = readAddress(input)

            when (cmd) {
                CMD_CONNECT.toInt() and 0xFF -> handleConnect(client, input, output, host, port)
                else -> sendReply(output, REPLY_CMD_NOT_SUPPORTED)
            }
        } catch (e: IOException) {
            log.fine("Client handler error: ${e.message}")
        } finally {
            client.closeSilently()
        }
    }

    internal fun negotiate(input: InputStream, output: OutputStream): Boolean {
        val version = input.read()
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

    internal fun readAddress(input: InputStream): Pair<String, Int> {
        val addrType = input.read()
        val host = when (addrType) {
            ADDR_IPV4.toInt() and 0xFF -> {
                val addr = ByteArray(4)
                readFully(input, addr)
                InetAddress.getByAddress(addr).hostAddress ?: "0.0.0.0"
            }
            ADDR_DOMAIN.toInt() and 0xFF -> {
                val len = input.read()
                if (len <= 0) throw IOException("Invalid domain length")
                val domain = ByteArray(len)
                readFully(input, domain)
                String(domain, Charsets.US_ASCII)
            }
            ADDR_IPV6.toInt() and 0xFF -> {
                val addr = ByteArray(16)
                readFully(input, addr)
                InetAddress.getByAddress(addr).hostAddress ?: "::0"
            }
            else -> throw IOException("Unsupported address type: $addrType")
        }

        val portHigh = input.read()
        val portLow = input.read()
        if (portHigh == -1 || portLow == -1) throw IOException("Unexpected end of stream reading port")
        val port = (portHigh shl 8) or portLow

        return host to port
    }

    internal fun handleConnect(
        client: Socket,
        input: InputStream,
        output: OutputStream,
        host: String,
        port: Int,
    ) {
        val factory = socketFactoryProvider() ?: run {
            sendReply(output, REPLY_GENERAL_FAILURE)
            return
        }

        val resolved = try {
            dnsResolver(host)
        } catch (e: Exception) {
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
            upstream.soTimeout = 60_000
        } catch (_: IOException) {
            sendReply(output, REPLY_CONNECTION_REFUSED)
            return
        }

        try {
            val localAddr = upstream.localAddress
            val localPort = upstream.localPort
            sendReply(output, REPLY_SUCCESS, localAddr, localPort)

            val clientToServer = Thread {
                relay(input, upstream.getOutputStream())
            }
            val serverToClient = Thread {
                relay(upstream.getInputStream(), client.getOutputStream())
            }
            clientToServer.start()
            serverToClient.start()
            clientToServer.join()
            serverToClient.join()
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
            upstream.soTimeout = 60_000
        } catch (_: IOException) {
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
            if (n == -1) throw IOException("Unexpected end of stream")
            offset += n
        }
    }

    private fun Socket.closeSilently() {
        try { close() } catch (_: IOException) { }
    }

    companion object {
        const val VERSION: Byte = 0x05
        const val AUTH_NONE: Byte = 0x00
        val AUTH_NO_ACCEPTABLE: Byte = 0xFF.toByte()
        const val CMD_CONNECT: Byte = 0x01
        const val ADDR_IPV4: Byte = 0x01
        const val ADDR_DOMAIN: Byte = 0x03
        const val ADDR_IPV6: Byte = 0x04
        const val REPLY_SUCCESS: Byte = 0x00
        const val REPLY_GENERAL_FAILURE: Byte = 0x01
        const val REPLY_HOST_UNREACHABLE: Byte = 0x04
        const val REPLY_CONNECTION_REFUSED: Byte = 0x05
        const val REPLY_CMD_NOT_SUPPORTED: Byte = 0x07
        const val REPLY_ADDR_NOT_SUPPORTED: Byte = 0x08
        private val log = Logger.getLogger(Socks5Server::class.java.name)
    }
}
