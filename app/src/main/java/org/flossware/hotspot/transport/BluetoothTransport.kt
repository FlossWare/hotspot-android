package org.flossware.hotspot.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.flossware.hotspot.service.BluetoothServer
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [FlossTransport] backed by Bluetooth RFCOMM, using the same UUID and
 * service name as [BluetoothServer].
 *
 * Outbound connections use Bluetooth RFCOMM with the same UUID that the
 * [BluetoothServer] listens on. Inbound sessions are accepted via
 * [listen].
 */
class BluetoothTransport(
    private val adapter: BluetoothAdapter?,
) : FlossTransport {

    @SuppressLint("MissingPermission")
    override suspend fun connect(peer: PeerIdentity): TransportSession {
        val macAddress = peer.endpoints[TransportType.BLUETOOTH]
            ?: throw TransportDisconnectedException("No Bluetooth endpoint for peer ${peer.label}")

        val localAdapter = adapter
            ?: throw TransportDisconnectedException("Bluetooth is not available")

        val device = localAdapter.getRemoteDevice(macAddress)
        val socket = try {
            device.createRfcommSocketToServiceRecord(BluetoothServer.SERVICE_UUID).also {
                it.connect()
            }
        } catch (e: IOException) {
            throw TransportDisconnectedException("Bluetooth connect failed for $macAddress", e)
        }

        return BluetoothSession(peer, socket)
    }

    @SuppressLint("MissingPermission")
    override suspend fun listen(): Flow<TransportSession> = callbackFlow {
        val localAdapter = adapter
            ?: throw TransportDisconnectedException("Bluetooth is not available")

        val serverSocket = try {
            localAdapter.listenUsingRfcommWithServiceRecord(
                BluetoothServer.SERVICE_NAME,
                BluetoothServer.SERVICE_UUID,
            )
        } catch (e: IOException) {
            throw TransportDisconnectedException("Failed to create Bluetooth server socket", e)
        }

        val running = AtomicBoolean(true)
        val acceptThread = Thread {
            while (running.get()) {
                try {
                    val btSocket = serverSocket.accept()
                    val peer = PeerIdentity(
                        publicKeyFingerprint = "",
                        label = btSocket.remoteDevice?.name ?: btSocket.remoteDevice?.address ?: "unknown",
                        endpoints = mapOf(
                            TransportType.BLUETOOTH to (btSocket.remoteDevice?.address ?: ""),
                        ),
                    )
                    trySend(BluetoothSession(peer, btSocket))
                } catch (_: IOException) {
                    if (running.get()) break
                }
            }
        }
        acceptThread.isDaemon = true
        acceptThread.start()

        awaitClose {
            running.set(false)
            try {
                serverSocket.close()
            } catch (_: IOException) {
                // already closed
            }
            acceptThread.interrupt()
        }
    }

    override fun availableTransports(): List<TransportType> = listOf(TransportType.BLUETOOTH)

    override fun preferredTransport(hint: QosHint): TransportType = TransportType.BLUETOOTH

    companion object {
        private const val BUFFER_SIZE = 8192
    }

    /**
     * A [TransportSession] backed by a Bluetooth RFCOMM [BluetoothSocket].
     */
    private class BluetoothSession(
        override val peer: PeerIdentity,
        private val socket: BluetoothSocket,
    ) : TransportSession {

        override val transport: TransportType = TransportType.BLUETOOTH

        private val sendMutex = Mutex()

        override val isConnected: Boolean
            get() = socket.isConnected

        override suspend fun send(data: ByteArray) {
            if (!isConnected) throw TransportDisconnectedException()
            sendMutex.withLock {
                try {
                    socket.outputStream.write(data)
                    socket.outputStream.flush()
                } catch (e: IOException) {
                    throw TransportDisconnectedException("Bluetooth send failed", e)
                }
            }
        }

        override fun receive(): Flow<ByteArray> = flow {
            val buffer = ByteArray(BUFFER_SIZE)
            try {
                while (isConnected) {
                    val count = socket.inputStream.read(buffer)
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
