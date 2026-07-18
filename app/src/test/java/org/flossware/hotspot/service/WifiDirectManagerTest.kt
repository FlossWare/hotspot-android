package org.flossware.hotspot.service

import android.net.wifi.p2p.WifiP2pManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDirectManagerTest {

    @Test
    fun `mapFailureReason returns user-friendly message for ERROR`() {
        val msg = WifiDirectManager.mapFailureReason(WifiP2pManager.ERROR)
        assertTrue(msg.contains("Wi-Fi Direct failed"))
        assertTrue(msg.contains("Wi-Fi to be ON"))
    }

    @Test
    fun `mapFailureReason returns user-friendly message for P2P_UNSUPPORTED`() {
        val msg = WifiDirectManager.mapFailureReason(WifiP2pManager.P2P_UNSUPPORTED)
        assertTrue(msg.contains("not supported"))
    }

    @Test
    fun `mapFailureReason returns user-friendly message for BUSY`() {
        val msg = WifiDirectManager.mapFailureReason(WifiP2pManager.BUSY)
        assertTrue(msg.contains("busy"))
        assertTrue(msg.contains("try again"))
    }

    @Test
    fun `mapFailureReason returns user-friendly message for NO_SERVICE_REQUESTS`() {
        val msg = WifiDirectManager.mapFailureReason(WifiP2pManager.NO_SERVICE_REQUESTS)
        assertTrue(msg.contains("Service discovery failed"))
    }

    @Test
    fun `mapFailureReason returns generic message for unknown reason`() {
        val msg = WifiDirectManager.mapFailureReason(99)
        assertTrue(msg.contains("error code: 99"))
    }

    @Test
    fun `MAX_RETRIES is reasonable`() {
        assertEquals(2, WifiDirectManager.MAX_RETRIES)
    }
}
