package org.flossware.hotspot.proxy

import android.net.Network
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
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

class ProxyServer(
    private val bindAddress: InetAddress,
    private val port: Int = 8080,
    private val networkProvider: () -> Network?,
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
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 60_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ", limit = 3)
            if (parts.size < 3) {
                sendError(client, 400, "Bad Request")
                return
            }

            val method = parts[0].uppercase()
            val target = parts[1]

            val headers = readHeaders(reader)

            when (method) {
                "CONNECT" -> handleConnect(client, target)
                else -> handleHttp(client, method, target, headers, reader)
            }
        } catch (e: IOException) {
            Log.d(TAG, "Client handler error: ${e.message}")
        } finally {
            client.closeSilently()
        }
    }

    private fun handleConnect(client: Socket, target: String) {
        val (host, port) = parseHostPort(target, 443)
        val network = networkProvider() ?: run {
            sendError(client, 502, "No mobile network")
            return
        }

        val upstream: Socket
        try {
            upstream = network.socketFactory.createSocket(host, port)
            upstream.soTimeout = 60_000
        } catch (e: IOException) {
            sendError(client, 502, "Bad Gateway")
            return
        }

        try {
            val response = "HTTP/1.1 200 Connection Established\r\n\r\n"
            client.getOutputStream().write(response.toByteArray())
            client.getOutputStream().flush()

            val clientToServer = Thread {
                relay(client.getInputStream(), upstream.getOutputStream())
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

    private fun handleHttp(
        client: Socket,
        method: String,
        urlString: String,
        headers: Map<String, String>,
        reader: BufferedReader,
    ) {
        val network = networkProvider() ?: run {
            sendError(client, 502, "No mobile network")
            return
        }

        val url = try {
            URL(urlString)
        } catch (_: Exception) {
            sendError(client, 400, "Invalid URL")
            return
        }

        val host = url.host
        val port = if (url.port != -1) url.port else 80
        val path = if (url.query != null) "${url.path}?${url.query}" else url.path.ifEmpty { "/" }

        val upstream: Socket
        try {
            upstream = network.socketFactory.createSocket(host, port)
            upstream.soTimeout = 60_000
        } catch (e: IOException) {
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
                relayBytes(client.getInputStream(), out, contentLength)
            }
            out.flush()

            relay(upstream.getInputStream(), client.getOutputStream())
        } finally {
            upstream.closeSilently()
        }
    }

    private fun relay(input: InputStream, output: OutputStream) {
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
            // connection closed
        }
    }

    private fun relayBytes(input: InputStream, output: OutputStream, length: Long) {
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
        } catch (_: IOException) {
        }
    }

    private fun readHeaders(reader: BufferedReader): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
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

    private fun parseHostPort(target: String, defaultPort: Int): Pair<String, Int> {
        val colonIndex = target.lastIndexOf(':')
        return if (colonIndex > 0) {
            val host = target.substring(0, colonIndex)
            val port = target.substring(colonIndex + 1).toIntOrNull() ?: defaultPort
            host to port
        } else {
            target to defaultPort
        }
    }

    private fun sendError(client: Socket, code: Int, message: String) {
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
        } catch (_: IOException) {
        }
    }

    private fun Socket.closeSilently() {
        try { close() } catch (_: IOException) { }
    }

    companion object {
        private const val TAG = "ProxyServer"
    }
}
