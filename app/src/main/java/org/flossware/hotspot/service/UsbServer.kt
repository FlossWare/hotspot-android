package org.flossware.hotspot.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.ParcelFileDescriptor
import timber.log.Timber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

sealed class UsbState {
    data object Idle : UsbState()
    data class Connected(val accessoryName: String) : UsbState()
    data class Error(val message: String) : UsbState()
}

class UsbServer(
    @Volatile var debugMode: Boolean = false,
) {
    private val _state = MutableStateFlow<UsbState>(UsbState.Idle)
    val state: StateFlow<UsbState> = _state.asStateFlow()

    private val running = AtomicBoolean(false)
    private var accessoryFd: ParcelFileDescriptor? = null
    private var receiver: BroadcastReceiver? = null
    private val executor = ThreadPoolExecutor(
        2, 8, 60L, TimeUnit.SECONDS, LinkedBlockingQueue(16),
        ThreadPoolExecutor.CallerRunsPolicy(),
    )

    fun start(context: Context, socksPort: Int = 1080) {
        if (running.getAndSet(true)) return

        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usbManager == null) {
            Timber.tag(TAG).w( "USB not supported on this device")
            _state.value = UsbState.Error("USB not supported")
            running.set(false)
            return
        }

        val usbReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                        val accessory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                UsbManager.EXTRA_ACCESSORY,
                                UsbAccessory::class.java,
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
                        }
                        if (accessory != null) {
                            openAccessory(usbManager, accessory, socksPort)
                        }
                    }
                    UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                        closeAccessory()
                        _state.value = UsbState.Idle
                        Timber.tag(TAG).i( "USB accessory detached")
                    }
                }
            }
        }
        receiver = usbReceiver

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }

        // Check if an accessory is already connected
        val accessories = usbManager.accessoryList
        if (!accessories.isNullOrEmpty()) {
            openAccessory(usbManager, accessories[0], socksPort)
        }

        Timber.tag(TAG).i( "USB server started, waiting for accessory")
    }

    fun stop() {
        running.set(false)
        closeAccessory()
        executor.shutdownNow()
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Timber.tag(TAG).w( "Executor did not terminate within ${SHUTDOWN_TIMEOUT_MS}ms")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        _state.value = UsbState.Idle
        Timber.tag(TAG).i( "USB server stopped")
    }

    fun unregisterReceiver(context: Context) {
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
                // Receiver not registered
            }
        }
        receiver = null
    }

    private fun openAccessory(usbManager: UsbManager, accessory: UsbAccessory, socksPort: Int) {
        closeAccessory()

        val pfd = usbManager.openAccessory(accessory)
        if (pfd == null) {
            Timber.tag(TAG).e( "Failed to open USB accessory")
            _state.value = UsbState.Error("Failed to open USB accessory")
            return
        }
        accessoryFd = pfd

        val name = accessory.description ?: accessory.model ?: "USB Accessory"
        _state.value = UsbState.Connected(name)
        Timber.tag(TAG).i( "USB accessory connected: $name")

        executor.execute { relayToSocks(pfd, socksPort) }
    }

    private fun relayToSocks(pfd: ParcelFileDescriptor, socksPort: Int) {
        val usbInput = FileInputStream(pfd.fileDescriptor)
        val usbOutput = FileOutputStream(pfd.fileDescriptor)
        var tcpSocket: Socket? = null

        try {
            tcpSocket = Socket(InetAddress.getLoopbackAddress(), socksPort)
            tcpSocket.soTimeout = 60_000

            val usbToTcp = Thread {
                try {
                    relay(usbInput, tcpSocket.getOutputStream())
                } finally {
                    tcpSocket.closeSilently()
                }
            }
            val tcpToUsb = Thread {
                try {
                    relay(tcpSocket.getInputStream(), usbOutput)
                } finally {
                    pfd.closeSilently()
                }
            }

            usbToTcp.start()
            tcpToUsb.start()
            usbToTcp.join()
            tcpToUsb.join()
        } catch (e: IOException) {
            Timber.tag(TAG).w( "USB relay error: ${e.message}")
        } finally {
            tcpSocket?.closeSilently()
            usbInput.closeSilently()
            usbOutput.closeSilently()
            if (debugMode) Timber.tag(TAG).d( "USB relay connection closed")
        }
    }

    private fun closeAccessory() {
        accessoryFd?.closeSilently()
        accessoryFd = null
    }

    companion object {
        private const val TAG = "UsbServer"
        private const val RELAY_BUFFER_SIZE = 8192
        private const val SHUTDOWN_TIMEOUT_MS = 3000L

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

        private fun ParcelFileDescriptor.closeSilently() {
            try {
                close()
            } catch (_: IOException) {
                // Already closed
            }
        }

        private fun FileInputStream.closeSilently() {
            try {
                close()
            } catch (_: IOException) {
                // Already closed
            }
        }

        private fun FileOutputStream.closeSilently() {
            try {
                close()
            } catch (_: IOException) {
                // Already closed
            }
        }
    }
}
