package org.flossware.hotspot.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import timber.log.Timber
import kotlinx.coroutines.flow.MutableStateFlow
import org.flossware.hotspot.R
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

sealed class BluetoothState {
    data object Idle : BluetoothState()
    data class Listening(val deviceName: String) : BluetoothState()
    data class Error(val message: String) : BluetoothState()
}

class BluetoothServer(
    @Volatile var debugMode: Boolean = false,
) {
    private var unknownDeviceName: String = "Unknown"

    private val _state = MutableStateFlow<BluetoothState>(BluetoothState.Idle)
    val state: StateFlow<BluetoothState> = _state.asStateFlow()

    private val _connectedDevices = MutableStateFlow<List<ConnectedDevice>>(emptyList())
    val connectedDevices: StateFlow<List<ConnectedDevice>> = _connectedDevices.asStateFlow()

    private val running = AtomicBoolean(false)
    private var serverSocket: BluetoothServerSocket? = null
    private val activeConnections = CopyOnWriteArrayList<BluetoothSocket>()
    private val executor = ThreadPoolExecutor(
        2, 16, 60L, TimeUnit.SECONDS, LinkedBlockingQueue(16),
        ThreadPoolExecutor.CallerRunsPolicy(),
    )

    @SuppressLint("MissingPermission")
    fun start(context: Context, socksPort: Int = 1080) {
        if (running.getAndSet(true)) return

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter
        if (adapter == null) {
            Timber.tag(TAG).w("Bluetooth not supported on this device")
            _state.value = BluetoothState.Error(context.getString(R.string.error_bluetooth_not_supported))
            running.set(false)
            return
        }
        if (!adapter.isEnabled) {
            Timber.tag(TAG).w("Bluetooth is disabled")
            _state.value = BluetoothState.Error(context.getString(R.string.error_bluetooth_disabled))
            running.set(false)
            return
        }

        try {
            serverSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
        } catch (e: IOException) {
            Timber.tag(TAG).e(e, "Failed to create Bluetooth server")
            _state.value = BluetoothState.Error(context.getString(R.string.error_bluetooth_server_failed, e.message ?: ""))
            running.set(false)
            return
        }

        unknownDeviceName = context.getString(R.string.unknown_device)
        _state.value = BluetoothState.Listening(adapter.name ?: unknownDeviceName)
        Timber.tag(TAG).i("transport_connect event=bluetooth_listen name=%s", adapter.name)

        executor.execute {
            while (running.get()) {
                try {
                    val btSocket = serverSocket?.accept() ?: break
                    executor.execute { handleClient(btSocket, socksPort) }
                } catch (e: IOException) {
                    if (running.get()) {
                        Timber.tag(TAG).w("Bluetooth accept failed: %s", e.message)
                    }
                    break
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Timber.tag(TAG).d("Server socket close: %s", e.message)
        }
        serverSocket = null
        for (conn in activeConnections) {
            try {
                conn.close()
            } catch (e: IOException) {
                Timber.tag(TAG).d("Connection close: %s", e.message)
            }
        }
        activeConnections.clear()
        _connectedDevices.value = emptyList()
        executor.shutdownNow()
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Timber.tag(TAG).w("Executor did not terminate within %dms", SHUTDOWN_TIMEOUT_MS)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        _state.value = BluetoothState.Idle
        Timber.tag(TAG).i("transport_disconnect event=bluetooth_server_stopped")
    }

    @SuppressLint("MissingPermission")
    private fun handleClient(btSocket: BluetoothSocket, socksPort: Int) {
        // Reject unbonded devices to prevent unauthorized access
        if (btSocket.remoteDevice.bondState != BluetoothDevice.BOND_BONDED) {
            Timber.tag(TAG).w("Rejecting unbonded device: %s", btSocket.remoteDevice.address)
            btSocket.closeSilently()
            return
        }

        val device = ConnectedDevice(
            macAddress = btSocket.remoteDevice.address,
            deviceName = btSocket.remoteDevice.name ?: unknownDeviceName,
        )
        activeConnections.add(btSocket)
        _connectedDevices.value = activeConnections.map { sock ->
            ConnectedDevice(
                macAddress = sock.remoteDevice.address,
                deviceName = sock.remoteDevice.name ?: unknownDeviceName,
            )
        }

        Timber.tag(TAG).i("transport_connect event=bluetooth_client_connected device=%s", device.deviceName)

        var tcpSocket: Socket? = null
        try {
            tcpSocket = Socket(InetAddress.getLoopbackAddress(), socksPort)
            tcpSocket.soTimeout = 60_000

            val btToTcp = Thread {
                try {
                    relay(btSocket.inputStream, tcpSocket.getOutputStream())
                } finally {
                    tcpSocket.closeSilently()
                }
            }
            val tcpToBt = Thread {
                try {
                    relay(tcpSocket.getInputStream(), btSocket.outputStream)
                } finally {
                    btSocket.closeSilently()
                }
            }
            btToTcp.start()
            tcpToBt.start()
            btToTcp.join()
            tcpToBt.join()
        } catch (e: IOException) {
            Timber.tag(TAG).w("Bluetooth relay error for %s: %s", device.deviceName, e.message)
        } finally {
            tcpSocket?.closeSilently()
            btSocket.closeSilently()
            activeConnections.remove(btSocket)
            _connectedDevices.value = activeConnections.map { sock ->
                @SuppressLint("MissingPermission")
                ConnectedDevice(
                    macAddress = sock.remoteDevice.address,
                    deviceName = sock.remoteDevice.name ?: unknownDeviceName,
                )
            }
            Timber.tag(TAG).i("transport_disconnect event=bluetooth_client_disconnected device=%s", device.deviceName)
        }
    }

    companion object {
        private const val TAG = "BluetoothServer"
        private const val SHUTDOWN_TIMEOUT_MS = 3000L
        val SERVICE_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        const val SERVICE_NAME = "FlossHotspotSOCKS"
        private const val RELAY_BUFFER_SIZE = 8192

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
                // Expected during connection teardown
            }
        }

        private fun Socket.closeSilently() {
            try {
                close()
            } catch (_: IOException) {
                // Socket already closed
            }
        }

        private fun BluetoothSocket.closeSilently() {
            try {
                close()
            } catch (_: IOException) {
                // Socket already closed
            }
        }
    }
}
