package org.flossware.hotspot.proxy

import android.net.Network
import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class DnsRelay(
    private val bindAddress: InetAddress,
    private val listenPort: Int = 5353,
    private val upstreamDnsProvider: () -> InetAddress,
    private val networkProvider: () -> Network?,
) {
    private var socket: DatagramSocket? = null
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread {
            try {
                val sock = DatagramSocket(listenPort, bindAddress)
                sock.soTimeout = 1000
                socket = sock
                Log.i(TAG, "DNS relay listening on $bindAddress:$listenPort")

                val buffer = ByteArray(4096)
                while (running.get()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        sock.receive(packet)
                    } catch (_: java.net.SocketTimeoutException) {
                        continue
                    }

                    val clientAddr = packet.address
                    val clientPort = packet.port
                    val queryData = packet.data.copyOf(packet.length)

                    Thread {
                        forwardQuery(sock, queryData, clientAddr, clientPort)
                    }.start()
                }
            } catch (e: IOException) {
                if (running.get()) {
                    Log.e(TAG, "DNS relay error", e)
                }
            }
        }.apply {
            name = "dns-relay"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        socket?.close()
        socket = null
        thread?.interrupt()
        thread = null
    }

    private fun forwardQuery(
        listenSocket: DatagramSocket,
        queryData: ByteArray,
        clientAddr: InetAddress,
        clientPort: Int,
    ) {
        var upstream: DatagramSocket? = null
        try {
            upstream = DatagramSocket()
            upstream.soTimeout = 5000

            val network = networkProvider()
            network?.bindSocket(upstream)

            val dnsServer = upstreamDnsProvider()
            val queryPacket = DatagramPacket(queryData, queryData.size, dnsServer, 53)
            upstream.send(queryPacket)

            val responseBuffer = ByteArray(4096)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            upstream.receive(responsePacket)

            val replyPacket = DatagramPacket(
                responsePacket.data,
                responsePacket.length,
                clientAddr,
                clientPort,
            )
            synchronized(listenSocket) {
                listenSocket.send(replyPacket)
            }
        } catch (e: IOException) {
            Log.d(TAG, "DNS forward failed: ${e.message}")
        } finally {
            upstream?.close()
        }
    }

    companion object {
        private const val TAG = "DnsRelay"
    }
}
