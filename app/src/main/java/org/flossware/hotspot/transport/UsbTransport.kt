package org.flossware.hotspot.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.flossware.hotspot.service.UsbServer
import org.flossware.hotspot.service.UsbState
import java.io.IOException
import java.net.InetAddress
import java.net.Socket

/**
 * [FlossTransport] backed by the existing [UsbServer].
 *
 * USB on Android uses the accessory protocol, which is inherently
 * point-to-point.  This transport establishes a TCP socket to the
 * local SOCKS proxy that the [UsbServer] relays traffic through.
 */
class UsbTransport(
    private val usbServer: UsbServer,
) : FlossTransport {

    override suspend fun connect(peer: PeerIdentity): TransportSession {
        val currentState = usbServer.state.value
        if (currentState !is UsbState.Connected) {
            throw TransportDisconnectedException("USB accessory is not connected")
        }

        return try {
            val socket = Socket(InetAddress.getLoopbackAddress(), DEFAULT_SOCKS_PORT)
            UsbSession(peer, socket)
        } catch (e: IOException) {
            throw TransportDisconnectedException("Failed to connect via USB", e)
        }
    }

    /**
     * USB accessory protocol does not support listening for arbitrary
     * inbound connections. Returns an empty flow.
     */
    override suspend fun listen(): Flow<TransportSession> = emptyFlow()

    override fun availableTransports(): List<TransportType> = listOf(TransportType.USB)

    override fun preferredTransport(hint: QosHint): TransportType = TransportType.USB

    companion object {
        private const val DEFAULT_SOCKS_PORT = 1080
        private const val BUFFER_SIZE = 8192
    }

    /**
     * A [TransportSession] backed by a TCP [Socket] tunnelled through USB.
     */
    private class UsbSession(
        override val peer: PeerIdentity,
        private val socket: Socket,
    ) : TransportSession {

        override val transport: TransportType = TransportType.USB

        private val sendMutex = Mutex()

        override val isConnected: Boolean
            get() = socket.isConnected && !socket.isClosed

        override suspend fun send(data: ByteArray) {
            if (!isConnected) throw TransportDisconnectedException()
            sendMutex.withLock {
                try {
                    socket.getOutputStream().write(data)
                    socket.getOutputStream().flush()
                } catch (e: IOException) {
                    throw TransportDisconnectedException("USB send failed", e)
                }
            }
        }

        override fun receive(): Flow<ByteArray> = flow {
            val buffer = ByteArray(BUFFER_SIZE)
            val input = socket.getInputStream()
            try {
                while (isConnected) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    emit(buffer.copyOf(count))
                }
            } catch (_: IOException) {
                // connection closed
            }
        }

        override suspend fun close() {
            try {
                socket.close()
            } catch (_: IOException) {
                // already closed
            }
        }
    }
}
