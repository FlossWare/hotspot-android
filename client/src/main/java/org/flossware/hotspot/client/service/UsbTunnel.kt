package org.flossware.hotspot.client.service

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import timber.log.Timber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

sealed class UsbTunnelState {
    data object Disconnected : UsbTunnelState()
    data object Connecting : UsbTunnelState()
    data class Connected(val localPort: Int) : UsbTunnelState()
    data class Error(val message: String) : UsbTunnelState()
}

class UsbTunnel(
    private val usbManager: UsbManager,
    private val usbDevice: UsbDevice,
    @Volatile var debugMode: Boolean = false,
) {
    private val _state = MutableStateFlow<UsbTunnelState>(UsbTunnelState.Disconnected)
    val state: StateFlow<UsbTunnelState> = _state.asStateFlow()

    private val running = AtomicBoolean(false)
    private var localServer: ServerSocket? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var bulkIn: UsbEndpoint? = null
    private var bulkOut: UsbEndpoint? = null
    private val activeConnections = CopyOnWriteArrayList<Socket>()
    private val executor = ThreadPoolExecutor(
        2, 16, 60L, TimeUnit.SECONDS, LinkedBlockingQueue(16),
        ThreadPoolExecutor.CallerRunsPolicy(),
    )

    fun start() {
        if (running.getAndSet(true)) return
        _state.value = UsbTunnelState.Connecting

        executor.execute {
            try {
                // Find bulk endpoints
                val iface = usbDevice.getInterface(0)
                var inEp: UsbEndpoint? = null
                var outEp: UsbEndpoint? = null
                for (i in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(i)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) {
                            inEp = ep
                        } else {
                            outEp = ep
                        }
                    }
                }
                if (inEp == null || outEp == null) {
                    _state.value = UsbTunnelState.Error("USB device has no bulk endpoints")
                    running.set(false)
                    return@execute
                }
                bulkIn = inEp
                bulkOut = outEp

                // Open USB connection
                val connection = usbManager.openDevice(usbDevice)
                if (connection == null) {
                    _state.value = UsbTunnelState.Error("Failed to open USB device")
                    running.set(false)
                    return@execute
                }
                if (!connection.claimInterface(iface, true)) {
                    connection.close()
                    _state.value = UsbTunnelState.Error("Failed to claim USB interface")
                    running.set(false)
                    return@execute
                }
                usbConnection = connection

                // Open local server socket
                val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
                localServer = server
                val port = server.localPort

                _state.value = UsbTunnelState.Connected(port)
                Timber.tag(TAG).i( "USB tunnel local server on 127.0.0.1:$port")

                while (running.get()) {
                    try {
                        val localSocket = server.accept()
                        if (debugMode) {
                            Timber.tag(TAG).d( "New local connection on port $port")
                        }
                        activeConnections.add(localSocket)
                        executor.execute { handleConnection(localSocket, connection, inEp, outEp) }
                    } catch (_: SocketException) {
                        break
                    }
                }
            } catch (e: Exception) {
                _state.value = UsbTunnelState.Error(e.message ?: "USB connection failed")
                Timber.tag(TAG).e(e, "USB tunnel error")
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
        usbConnection?.close()
        usbConnection = null
        executor.shutdownNow()
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Timber.tag(TAG).w( "Executor did not terminate within ${SHUTDOWN_TIMEOUT_MS}ms")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        _state.value = UsbTunnelState.Disconnected
        Timber.tag(TAG).i( "USB tunnel stopped")
    }

    private fun handleConnection(
        localSocket: Socket,
        connection: UsbDeviceConnection,
        inEndpoint: UsbEndpoint,
        outEndpoint: UsbEndpoint,
    ) {
        try {
            val localToUsb = Thread {
                try {
                    val buffer = ByteArray(RELAY_BUFFER_SIZE)
                    val input = localSocket.getInputStream()
                    while (running.get()) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        val sent = connection.bulkTransfer(outEndpoint, buffer, count, TRANSFER_TIMEOUT_MS)
                        if (sent < 0) {
                            Timber.tag(TAG).w( "USB bulk send failed")
                            break
                        }
                    }
                } catch (_: IOException) {
                    // Expected during teardown
                } finally {
                    localSocket.closeSilently()
                }
            }
            val usbToLocal = Thread {
                try {
                    val buffer = ByteArray(RELAY_BUFFER_SIZE)
                    val output = localSocket.getOutputStream()
                    while (running.get()) {
                        val received = connection.bulkTransfer(inEndpoint, buffer, buffer.size, TRANSFER_TIMEOUT_MS)
                        if (received < 0) continue // timeout, retry
                        if (received == 0) continue
                        output.write(buffer, 0, received)
                        output.flush()
                    }
                } catch (_: IOException) {
                    // Expected during teardown
                } finally {
                    localSocket.closeSilently()
                }
            }
            localToUsb.start()
            usbToLocal.start()
            localToUsb.join()
            usbToLocal.join()
        } catch (e: Exception) {
            Timber.tag(TAG).w( "USB relay error: ${e.message}")
        } finally {
            localSocket.closeSilently()
            activeConnections.remove(localSocket)
            if (debugMode) Timber.tag(TAG).d( "USB relay connection closed")
        }
    }

    companion object {
        private const val TAG = "UsbTunnel"
        private const val RELAY_BUFFER_SIZE = 8192
        private const val TRANSFER_TIMEOUT_MS = 5000
        private const val SHUTDOWN_TIMEOUT_MS = 3000L

        private fun Socket.closeSilently() {
            try {
                close()
            } catch (_: IOException) {
                // Socket already closed
            }
        }

        private fun ServerSocket.closeSilently() {
            try {
                close()
            } catch (_: IOException) {
                // Socket already closed
            }
        }
    }
}
