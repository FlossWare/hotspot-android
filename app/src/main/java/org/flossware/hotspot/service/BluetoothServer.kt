package org.flossware.hotspot.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.flossware.hotspot.model.ConnectedDevice
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

sealed class BluetoothState {
    data object Idle : BluetoothState()
    data class Listening(val deviceName: String) : BluetoothState()
    data class Error(val message: String) : BluetoothState()
}

class BluetoothServer {
    private val _state = MutableStateFlow<BluetoothState>(BluetoothState.Idle)
    val state: StateFlow<BluetoothState> = _state.asStateFlow()

    private val _connectedDevices = MutableStateFlow<List<ConnectedDevice>>(emptyList())
    val connectedDevices: StateFlow<List<ConnectedDevice>> = _connectedDevices.asStateFlow()

    private val running = AtomicBoolean(false)
    private var serverSocket: BluetoothServerSocket? = null
    private val activeConnections = CopyOnWriteArrayList<BluetoothSocket>()
    private val executor = ThreadPoolExecutor(
        2, 16, 60L, TimeUnit.SECONDS, SynchronousQueue(),
        ThreadPoolExecutor.CallerRunsPolicy(),
    )

    @SuppressLint("MissingPermission")
    fun start(context: Context, socksPort: Int = 1080) {
        if (running.getAndSet(true)) return

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter
        if (adapter == null) {
            _state.value = BluetoothState.Error("Bluetooth not supported")
            running.set(false)
            return
        }
        if (!adapter.isEnabled) {
            _state.value = BluetoothState.Error("Bluetooth is disabled")
            running.set(false)
            return
        }

        try {
            serverSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
        } catch (e: IOException) {
            _state.value = BluetoothState.Error("Failed to create Bluetooth server: ${e.message}")
            running.set(false)
            return
        }

        _state.value = BluetoothState.Listening(adapter.name ?: "Unknown")
        log.info("Bluetooth RFCOMM listening as '${adapter.name}'")

        executor.execute {
            while (running.get()) {
                try {
                    val btSocket = serverSocket?.accept() ?: break
                    executor.execute { handleClient(btSocket, socksPort) }
                } catch (_: IOException) {
                    break
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: IOException) { }
        serverSocket = null
        for (conn in activeConnections) {
            try { conn.close() } catch (_: IOException) { }
        }
        activeConnections.clear()
        _connectedDevices.value = emptyList()
        executor.shutdownNow()
        _state.value = BluetoothState.Idle
        log.info("Bluetooth server stopped")
    }

    @SuppressLint("MissingPermission")
    private fun handleClient(btSocket: BluetoothSocket, socksPort: Int) {
        val device = ConnectedDevice(
            macAddress = btSocket.remoteDevice.address,
            deviceName = btSocket.remoteDevice.name ?: "Unknown",
        )
        activeConnections.add(btSocket)
        _connectedDevices.value = activeConnections.map { sock ->
            ConnectedDevice(
                macAddress = sock.remoteDevice.address,
                deviceName = sock.remoteDevice.name ?: "Unknown",
            )
        }

        log.info("Bluetooth client connected: ${device.deviceName} (${device.macAddress})")

        var tcpSocket: Socket? = null
        try {
            tcpSocket = Socket(InetAddress.getLoopbackAddress(), socksPort)
            tcpSocket.soTimeout = 60_000

            val btToTcp = Thread {
                relay(btSocket.inputStream, tcpSocket.getOutputStream())
            }
            val tcpToBt = Thread {
                relay(tcpSocket.getInputStream(), btSocket.outputStream)
            }
            btToTcp.start()
            tcpToBt.start()
            btToTcp.join()
            tcpToBt.join()
        } catch (e: IOException) {
            log.log(Level.FINE, "Bluetooth relay error: ${e.message}")
        } finally {
            tcpSocket?.closeSilently()
            btSocket.closeSilently()
            activeConnections.remove(btSocket)
            _connectedDevices.value = activeConnections.map { sock ->
                @SuppressLint("MissingPermission")
                ConnectedDevice(
                    macAddress = sock.remoteDevice.address,
                    deviceName = sock.remoteDevice.name ?: "Unknown",
                )
            }
            log.info("Bluetooth client disconnected: ${device.deviceName}")
        }
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        const val SERVICE_NAME = "FlossHotspotSOCKS"
        private const val RELAY_BUFFER_SIZE = 8192
        private val log = Logger.getLogger(BluetoothServer::class.java.name)

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

        private fun BluetoothSocket.closeSilently() {
            try { close() } catch (_: IOException) { }
        }
    }
}
