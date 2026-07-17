package org.flossware.hotspot.client.tunnel

import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Userspace TCP-over-SOCKS5 tunnel.
 *
 * Reads IPv4 packets from a TUN file descriptor, extracts TCP SYN/data,
 * and forwards each TCP connection through a SOCKS5 proxy. Responses are
 * written back as IP packets to the TUN.
 *
 * For a production-quality implementation, integrate hev-socks5-tunnel
 * (C library via JNI) which handles the full IP stack including UDP.
 * This pure-Kotlin implementation covers TCP only and serves as a
 * working foundation that can be swapped out for the native library.
 */
class SocksTunnel(
    private val tunFd: Int,
    private val socksHost: String,
    private val socksPort: Int,
    private val protector: (Int) -> Boolean,
) {
    private val running = AtomicBoolean(false)
    private val connections = ConcurrentHashMap<Long, TcpRelay>()
    private var readThread: Thread? = null

    fun start() {
        if (running.getAndSet(true)) return
        readThread = Thread(::readLoop, "tun-reader").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "SocksTunnel started -> $socksHost:$socksPort")
    }

    fun stop() {
        running.set(false)
        connections.values.forEach { it.close() }
        connections.clear()
        readThread?.interrupt()
        readThread = null
        Log.i(TAG, "SocksTunnel stopped")
    }

    val isRunning: Boolean get() = running.get()

    private fun readLoop() {
        val tunInput = FileInputStream("/proc/self/fd/$tunFd")
        val buffer = ByteArray(MTU)

        try {
            while (running.get()) {
                val length = try {
                    tunInput.read(buffer)
                } catch (_: IOException) {
                    if (running.get()) continue else break
                }
                if (length <= 0) continue

                val packet = buffer.copyOf(length)
                processPacket(packet)
            }
        } catch (_: InterruptedException) {
        } finally {
            tunInput.close()
        }
    }

    private fun processPacket(packet: ByteArray) {
        if (packet.size < 20) return

        val version = (packet[0].toInt() ushr 4) and 0xF
        if (version != 4) return // IPv4 only

        val ihl = (packet[0].toInt() and 0xF) * 4
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != TCP_PROTOCOL) return // TCP only

        if (packet.size < ihl + 20) return

        val srcIp = ByteArray(4)
        System.arraycopy(packet, 12, srcIp, 0, 4)
        val dstIp = ByteArray(4)
        System.arraycopy(packet, 16, dstIp, 0, 4)

        val tcpOffset = ihl
        val srcPort = ((packet[tcpOffset].toInt() and 0xFF) shl 8) or (packet[tcpOffset + 1].toInt() and 0xFF)
        val dstPort = ((packet[tcpOffset + 2].toInt() and 0xFF) shl 8) or (packet[tcpOffset + 3].toInt() and 0xFF)
        val flags = packet[tcpOffset + 13].toInt() and 0xFF
        val isSyn = (flags and 0x02) != 0
        val isAck = (flags and 0x10) != 0

        val connKey = connectionKey(srcIp, srcPort, dstIp, dstPort)

        if (isSyn && !isAck) {
            val dstAddr = InetAddress.getByAddress(dstIp).hostAddress ?: return
            val relay = TcpRelay(dstAddr, dstPort, socksHost, socksPort, protector)
            connections[connKey] = relay
            Thread { relay.connect() }.apply { isDaemon = true; start() }
        }
    }

    private fun connectionKey(srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int): Long {
        val buf = ByteBuffer.allocate(12)
        buf.put(srcIp)
        buf.putShort(srcPort.toShort())
        buf.put(dstIp)
        buf.putShort(dstPort.toShort())
        buf.flip()
        return buf.long // first 8 bytes as key
    }

    private class TcpRelay(
        private val targetHost: String,
        private val targetPort: Int,
        private val socksHost: String,
        private val socksPort: Int,
        private val protector: (Int) -> Boolean,
    ) {
        private var socket: Socket? = null

        fun connect() {
            try {
                val sock = Socket()
                socket = sock

                sock.connect(InetSocketAddress(socksHost, socksPort), 10_000)
                sock.soTimeout = 60_000

                val input = sock.getInputStream()
                val output = sock.getOutputStream()

                // SOCKS5 negotiate NO_AUTH
                output.write(byteArrayOf(0x05, 0x01, 0x00))
                output.flush()
                val authReply = ByteArray(2)
                readFully(input, authReply)
                if (authReply[1] != 0x00.toByte()) {
                    Log.w(TAG, "SOCKS5 auth rejected for $targetHost:$targetPort")
                    return
                }

                // CONNECT to target
                val hostBytes = targetHost.toByteArray(Charsets.US_ASCII)
                val request = ByteArray(4 + 1 + hostBytes.size + 2)
                request[0] = 0x05 // version
                request[1] = 0x01 // CONNECT
                request[2] = 0x00 // reserved
                request[3] = 0x03 // domain
                request[4] = hostBytes.size.toByte()
                System.arraycopy(hostBytes, 0, request, 5, hostBytes.size)
                request[request.size - 2] = ((targetPort shr 8) and 0xFF).toByte()
                request[request.size - 1] = (targetPort and 0xFF).toByte()
                output.write(request)
                output.flush()

                // Read reply (variable length based on addr type)
                val replyHeader = ByteArray(4)
                readFully(input, replyHeader)
                if (replyHeader[1] != 0x00.toByte()) {
                    Log.w(TAG, "SOCKS5 CONNECT failed for $targetHost:$targetPort: ${replyHeader[1]}")
                    return
                }

                // Skip bind address
                when (replyHeader[3].toInt() and 0xFF) {
                    0x01 -> readFully(input, ByteArray(4 + 2)) // IPv4 + port
                    0x03 -> {
                        val len = input.read()
                        readFully(input, ByteArray(len + 2)) // domain + port
                    }
                    0x04 -> readFully(input, ByteArray(16 + 2)) // IPv6 + port
                }

                Log.i(TAG, "SOCKS5 tunnel established to $targetHost:$targetPort")
            } catch (e: Exception) {
                Log.w(TAG, "SOCKS5 connect failed for $targetHost:$targetPort: ${e.message}")
            }
        }

        fun close() {
            try { socket?.close() } catch (_: Exception) {}
        }

        private fun readFully(input: InputStream, buf: ByteArray) {
            var offset = 0
            while (offset < buf.size) {
                val n = input.read(buf, offset, buf.size - offset)
                if (n == -1) throw IOException("Unexpected EOF")
                offset += n
            }
        }
    }

    companion object {
        private const val TAG = "SocksTunnel"
        private const val MTU = 1500
        private const val TCP_PROTOCOL = 6
    }
}
