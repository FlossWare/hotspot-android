package org.flossware.hotspot.client.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

sealed class BluetoothTunnelState {
    data object Disconnected : BluetoothTunnelState()
    data object Connecting : BluetoothTunnelState()
    data class Connected(val localPort: Int) : BluetoothTunnelState()
    data class Error(val message: String) : BluetoothTunnelState()
}

class BluetoothTunnel(private val remoteDevice: BluetoothDevice) {
    private val _state = MutableStateFlow<BluetoothTunnelState>(BluetoothTunnelState.Disconnected)
    val state: StateFlow<BluetoothTunnelState> = _state.asStateFlow()

    private val running = AtomicBoolean(false)
    private var localServer: ServerSocket? = null
    private val activeConnections = CopyOnWriteArrayList<Socket>()
    private val executor = ThreadPoolExecutor(
        2, 16, 60L, TimeUnit.SECONDS, LinkedBlockingQueue(16),
        ThreadPoolExecutor.CallerRunsPolicy(),
    )

    @SuppressLint("MissingPermission")
    fun start() {
        if (running.getAndSet(true)) return
        _state.value = BluetoothTunnelState.Connecting

        executor.execute {
            try {
                val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
                localServer = server
                val port = server.localPort

                // Verify we can connect to the host before advertising the port
                val testSocket = remoteDevice.createRfcommSocketToServiceRecord(SERVICE_UUID)
                testSocket.connect()
                testSocket.close()

                _state.value = BluetoothTunnelState.Connected(port)
                log.info("Bluetooth tunnel local server on 127.0.0.1:$port")

                while (running.get()) {
                    try {
                        val localSocket = server.accept()
                        activeConnections.add(localSocket)
                        executor.execute { handleConnection(localSocket) }
                    } catch (_: SocketException) {
                        break
                    }
                }
            } catch (e: IOException) {
                _state.value = BluetoothTunnelState.Error(e.message ?: "Bluetooth connection failed")
                log.info("Bluetooth tunnel error: ${e.message}")
            }
        }
    }

    fun stop() {
        running.set(false)
        localServer?.closeSilently()
        localServer = null
        for (conn in activeConnections) {
            conn.closeSilently()
        }
        activeConnections.clear()
        executor.shutdownNow()
        _state.value = BluetoothTunnelState.Disconnected
        log.info("Bluetooth tunnel stopped")
    }

    @SuppressLint("MissingPermission")
    private fun handleConnection(localSocket: Socket) {
        var btSocket: BluetoothSocket? = null
        try {
            btSocket = remoteDevice.createRfcommSocketToServiceRecord(SERVICE_UUID)
            btSocket.connect()

            val localToBt = Thread {
                try {
                    relay(localSocket.getInputStream(), btSocket.outputStream)
                } finally {
                    btSocket.closeSilently()
                }
            }
            val btToLocal = Thread {
                try {
                    relay(btSocket.inputStream, localSocket.getOutputStream())
                } finally {
                    localSocket.closeSilently()
                }
            }
            localToBt.start()
            btToLocal.start()
            localToBt.join()
            btToLocal.join()
        } catch (e: IOException) {
            log.fine("Bluetooth relay error: ${e.message}")
        } finally {
            btSocket?.closeSilently()
            localSocket.closeSilently()
            activeConnections.remove(localSocket)
        }
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        private const val RELAY_BUFFER_SIZE = 8192
        private val log = Logger.getLogger(BluetoothTunnel::class.java.name)

        internal fun relay(input: InputStream, output: OutputStream) {
            val buffer = ByteArray(RELAY_BUFFER_SIZE)
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    output.write(buffer, 0, count)
                    output.flush()
                }
            } catch (_: IOException) {
            }
        }

        private fun Socket.closeSilently() {
            try { close() } catch (_: IOException) { }
        }

        private fun ServerSocket.closeSilently() {
            try { close() } catch (_: IOException) { }
        }

        private fun BluetoothSocket.closeSilently() {
            try { close() } catch (_: IOException) { }
        }
    }
}
