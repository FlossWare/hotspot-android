package org.flossware.hotspot.transport

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.flossware.hotspot.service.WifiDirectManager
import org.flossware.hotspot.service.WifiDirectState
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [FlossTransport] backed by the existing [WifiDirectManager].
 *
 * The manager owns the Wi-Fi Direct group lifecycle; this class wraps
 * TCP socket I/O over the Wi-Fi Direct network for peer-to-peer data
 * transfer.
 */
class WifiDirectTransport(
    private val wifiDirectManager: WifiDirectManager,
) : FlossTransport {

    override suspend fun connect(peer: PeerIdentity): TransportSession {
        val endpoint = peer.endpoints[TransportType.WIFI_DIRECT]
            ?: throw TransportDisconnectedException("No Wi-Fi Direct endpoint for peer ${peer.label}")

        val parts = endpoint.split(":")
        val host = parts[0]
        val port = parts.getOrElse(1) { DEFAULT_PORT.toString() }.toInt()

        return try {
            val socket = Socket(InetAddress.getByName(host), port)
            WifiDirectSession(peer, socket)
        } catch (e: IOException) {
            throw TransportDisconnectedException("Failed to connect via Wi-Fi Direct to $endpoint", e)
        }
    }

    override suspend fun listen(): Flow<TransportSession> = callbackFlow {
        val currentState = wifiDirectManager.state.value
        if (currentState !is WifiDirectState.GroupCreated) {
            throw TransportDisconnectedException("Wi-Fi Direct group is not active")
        }

        val bindAddress = InetAddress.getByName(currentState.groupOwnerAddress)
        val serverSocket = ServerSocket(DEFAULT_PORT, BACKLOG, bindAddress)
        val running = AtomicBoolean(true)

        val acceptThread = Thread {
            while (running.get()) {
                try {
                    val clientSocket = serverSocket.accept()
                    val peer = PeerIdentity(
                        publicKeyFingerprint = "",
                        label = clientSocket.inetAddress.hostAddress ?: "unknown",
                        endpoints = mapOf(
                            TransportType.WIFI_DIRECT to clientSocket.inetAddress.hostAddress.orEmpty(),
                        ),
                    )
                    trySend(WifiDirectSession(peer, clientSocket))
                } catch (_: IOException) {
                    if (running.get()) break
                }
            }
        }
        acceptThread.isDaemon = true
        acceptThread.start()

        awaitClose {
            running.set(false)
            serverSocket.closeSilently()
            acceptThread.interrupt()
        }
    }

    override fun availableTransports(): List<TransportType> = listOf(TransportType.WIFI_DIRECT)

    override fun preferredTransport(hint: QosHint): TransportType = TransportType.WIFI_DIRECT

    companion object {
        internal const val DEFAULT_PORT = 7265
        private const val BACKLOG = 8
        private const val BUFFER_SIZE = 8192

        private fun ServerSocket.closeSilently() {
            try {
                close()
            } catch (_: IOException) {
                // already closed
            }
        }
    }

    /**
     * A [TransportSession] backed by a TCP [Socket] over Wi-Fi Direct.
     */
    private class WifiDirectSession(
        override val peer: PeerIdentity,
        private val socket: Socket,
    ) : TransportSession {

        override val transport: TransportType = TransportType.WIFI_DIRECT

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
                    throw TransportDisconnectedException("Wi-Fi Direct send failed", e)
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
