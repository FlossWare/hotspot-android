package org.flossware.hotspot.viewmodel

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.flow.StateFlow
import org.flossware.hotspot.model.HotspotState
import org.flossware.hotspot.service.HotspotService

class HotspotViewModel(application: Application) : AndroidViewModel(application) {

    val hotspotState: StateFlow<HotspotState> = HotspotService.state

    init {
        val optIn = HotspotService.getBluetoothOptIn(application)
        val current = hotspotState.value
        if (current.bluetoothOptIn != optIn) {
            // Sync initial state from persisted preference
            HotspotService.setBluetoothOptIn(application, optIn)
        }

        // Sync configured passphrase from persisted preference
        val savedPassphrase = HotspotService.getPassphrase(application)
        if (hotspotState.value.configuredPassphrase != savedPassphrase) {
            HotspotService.setPassphrase(application, savedPassphrase)
        }

        detectFeatureAvailability()
    }

    fun startHotspot() {
        HotspotService.start(getApplication())
    }

    fun startBluetoothOnly() {
        HotspotService.startBluetoothOnly(getApplication())
    }

    fun stopHotspot() {
        HotspotService.stop(getApplication())
    }

    fun setBluetoothOptIn(enabled: Boolean) {
        HotspotService.setBluetoothOptIn(getApplication(), enabled)
    }

    /**
     * Updates the Wi-Fi Direct passphrase. Only effective before starting the hotspot.
     * The passphrase must be at least 8 characters (WPA2 requirement).
     */
    fun setPassphrase(passphrase: String) {
        HotspotService.setPassphrase(getApplication(), passphrase)
    }

    /**
     * Detects which hardware features are available on this device and updates
     * the shared state so the UI can show/hide sections accordingly.
     */
    fun detectFeatureAvailability() {
        val app = getApplication<Application>()
        val pm = app.packageManager

        val wifiDirectAvailable = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

        val btManager = app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAvailable = btManager?.adapter != null

        val usbManager = app.getSystemService(Context.USB_SERVICE) as? UsbManager
        val usbAvailable = usbManager != null

        val cm = app.getSystemService(ConnectivityManager::class.java)
        val mobileDataAvailable = cm?.getNetworkCapabilities(cm.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

        HotspotService.updateFeatureAvailability(
            wifiDirectAvailable = wifiDirectAvailable,
            bluetoothAvailable = bluetoothAvailable,
            usbAvailable = usbAvailable,
            mobileDataAvailable = mobileDataAvailable,
        )
    }

    fun generateQrBitmap(state: HotspotState, size: Int = 512): Bitmap? {
        if (state.networkName.isEmpty()) return null
        val content = "WIFI:T:WPA;S:${escapeWifi(state.networkName)};P:${escapeWifi(state.passphrase)};;"
        return try {
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(
                        x, y,
                        if (matrix[x, y]) android.graphics.Color.BLACK
                        else android.graphics.Color.WHITE,
                    )
                }
            }
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    private fun escapeWifi(value: String): String {
        return value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace(":", "\\:")
    }
}
