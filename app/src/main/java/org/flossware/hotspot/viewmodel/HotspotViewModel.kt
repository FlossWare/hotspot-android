package org.flossware.hotspot.viewmodel

import android.app.Application
import android.graphics.Bitmap
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
    }

    fun startHotspot() {
        HotspotService.start(getApplication())
    }

    fun stopHotspot() {
        HotspotService.stop(getApplication())
    }

    fun setBluetoothOptIn(enabled: Boolean) {
        HotspotService.setBluetoothOptIn(getApplication(), enabled)
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
