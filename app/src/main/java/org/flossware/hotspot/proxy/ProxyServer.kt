package org.flossware.hotspot.proxy

import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URL
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory

class ProxyServer(
    private val bindAddress: InetAddress,
    private val port: Int = 8080,
    private val socketFactoryProvider: () -> SocketFactory?,
    @Volatile var debugMode: Boolean = false,
) {
    private var serverSocket: ServerSocket? = null
    private val executor = ThreadPoolExecutor(4, 32, 60L, TimeUnit.SECONDS, SynchronousQueue())
    private val running = AtomicBoolean(false)
    private val _bytesTransferred = AtomicLong(0)
    val bytesTransferred: Long get() = _bytesTransferred.get()

    fun start() {
        if (running.getAndSet(true)) return
        executor.execute {
            try {
                val ss = ServerSocket(port, 50, bindAddress)
                ss.soTimeout = 0
                serverSocket = ss
                Log.i(TAG, "Proxy listening on $bindAddress:$port")
                while (running.get()) {
                    try {
                        val client = ss.accept()
                        if (debugMode) {
                            Log.d(TAG, "New connection from ${client.inetAddress.hostAddress}:${client.port}")
                        }
                        executor.execute { handleClient(client) }
                    } catch (_: SocketException) {
                        break
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Proxy server error", e)
            }
        }
    }

    fun stop() {
        running.set(false)
        serverSocket?.close()
        serverSocket = null
        executor.shutdownNow()
        Log.i(TAG, "Proxy server stopped")
    }

    val isRunning: Boolean get() = running.get()

    internal fun handleClient(client: Socket) {
        val clientAddr = client.inetAddress?.hostAddress ?: "unknown"
        try {
            client.soTimeout = 60_000
            val input = BufferedInputStream(client.getInputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ", limit = 3)
            if (parts.size < 3) {
                Log.w(TAG, "Malformed request from $clientAddr: $requestLine")
                sendError(client, 400, "Bad Request")
                return
            }

            val method = parts[0].uppercase()
            val target = parts[1]

            if (debugMode) Log.d(TAG, "$method $target from $clientAddr")

            val headers = readHeaders(input)

            when (method) {
                "CONNECT" -> handleConnect(client, input, target)
                else -> handleHttp(client, input, method, target, headers)
            }
        } catch (e: IOException) {
            Log.d(TAG, "Client handler error for $clientAddr: ${e.message}")
        } finally {
            client.closeSilently()
        }
    }

    internal fun handleConnect(client: Socket, clientInput: InputStream, target: String) {
        val (host, port) = parseHostPort(target, 443)
        val factory = socketFactoryProvider() ?: run {
            Log.w(TAG, "No socket factory available for CONNECT to $target")
            sendError(client, 502, "No mobile network")
            return
        }

        val upstream: Socket
        try {
            upstream = factory.createSocket(host, port)
            upstream.soTimeout = 60_000
        } catch (e: IOException) {
            Log.w(TAG, "Connection failed to $target: ${e.message}")
            sendError(client, 502, "Bad Gateway")
            return
        }

        try {
            val response = "HTTP/1.1 200 Connection Established\r\n\r\n"
            client.getOutputStream().write(response.toByteArray())
            client.getOutputStream().flush()

            val clientToServer = Thread {
                relay(clientInput, upstream.getOutputStream())
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
        } finally {
            upstream.closeSilently()
        }
    }

    internal fun handleHttp(
        client: Socket,
        clientInput: InputStream,
        method: String,
        urlString: String,
        headers: Map<String, String>,
    ) {
        val factory = socketFactoryProvider() ?: run {
            Log.w(TAG, "No socket factory available for $method $urlString")
            sendError(client, 502, "No mobile network")
            return
        }

        val url = try {
            URL(urlString)
        } catch (e: Exception) {
            Log.w(TAG, "Invalid URL from client: $urlString")
            sendError(client, 400, "Invalid URL")
            return
        }

        val host = url.host
        val port = if (url.port != -1) url.port else 80
        val path = if (url.query != null) "${url.path}?${url.query}" else url.path.ifEmpty { "/" }

        val upstream: Socket
        try {
            upstream = factory.createSocket(host, port)
            upstream.soTimeout = 60_000
        } catch (e: IOException) {
            Log.w(TAG, "Connection failed to $host:$port: ${e.message}")
            sendError(client, 502, "Bad Gateway")
            return
        }

        try {
            val out = upstream.getOutputStream()
            val requestLine = "$method $path HTTP/1.1\r\n"
            out.write(requestLine.toByteArray())

            for ((key, value) in headers) {
                if (key.equals("Proxy-Connection", ignoreCase = true)) continue
                out.write("$key: $value\r\n".toByteArray())
            }
            if (!headers.keys.any { it.equals("Host", ignoreCase = true) }) {
                out.write("Host: $host\r\n".toByteArray())
            }
            out.write("Connection: close\r\n".toByteArray())
            out.write("\r\n".toByteArray())

            val contentLength = headers.entries
                .firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
                ?.value?.toLongOrNull() ?: 0
            if (contentLength > 0) {
                relayBytes(clientInput, out, contentLength)
            }
            out.flush()

            relay(upstream.getInputStream(), client.getOutputStream())
        } finally {
            upstream.closeSilently()
        }
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
        } catch (e: IOException) {
            if (debugMode) Log.d(TAG, "Relay stream closed: ${e.message}")
        }
    }

    internal fun relayBytes(input: InputStream, output: OutputStream, length: Long) {
        val buffer = ByteArray(8192)
        var remaining = length
        try {
            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val count = input.read(buffer, 0, toRead)
                if (count == -1) break
                output.write(buffer, 0, count)
                remaining -= count
                _bytesTransferred.addAndGet(count.toLong())
            }
        } catch (e: IOException) {
            if (debugMode) Log.d(TAG, "Relay bytes stream closed: ${e.message}")
        }
    }

    internal fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    internal fun readHeaders(input: InputStream): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                val key = line.substring(0, colonIndex).trim()
                val value = line.substring(colonIndex + 1).trim()
                headers[key] = value
            }
        }
        return headers
    }

    internal fun parseHostPort(target: String, defaultPort: Int): Pair<String, Int> {
        val colonIndex = target.lastIndexOf(':')
        return if (colonIndex > 0) {
            val host = target.substring(0, colonIndex)
            val port = target.substring(colonIndex + 1).toIntOrNull() ?: defaultPort
            host to port
        } else {
            target to defaultPort
        }
    }

    internal fun sendError(client: Socket, code: Int, message: String) {
        try {
            val body = "<html><body><h1>$code $message</h1></body></html>"
            val response = "HTTP/1.1 $code $message\r\n" +
                "Content-Type: text/html\r\n" +
                "Content-Length: ${body.length}\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                body
            client.getOutputStream().write(response.toByteArray())
            client.getOutputStream().flush()
        } catch (e: IOException) {
            if (debugMode) Log.d(TAG, "Failed to send error response: ${e.message}")
        }
    }

    private fun Socket.closeSilently() {
        try {
            close()
        } catch (e: IOException) {
            if (debugMode) Log.d(TAG, "Socket close: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ProxyServer"
    }
}
