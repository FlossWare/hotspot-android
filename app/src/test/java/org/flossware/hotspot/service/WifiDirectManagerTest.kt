package org.flossware.hotspot.service

import android.net.wifi.p2p.WifiP2pManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.flossware.hotspot.model.ConnectedDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    // --- WifiDirectState sealed class tests ---

    @Test
    fun `WifiDirectState Idle is a singleton object`() {
        val idle1 = WifiDirectState.Idle
        val idle2 = WifiDirectState.Idle
        assertEquals(idle1, idle2)
    }

    @Test
    fun `WifiDirectState GroupCreated holds network info`() {
        val state = WifiDirectState.GroupCreated(
            networkName = "DIRECT-FW-Test",
            passphrase = "TestPass123",
            groupOwnerAddress = "192.168.49.1",
        )
        assertEquals("DIRECT-FW-Test", state.networkName)
        assertEquals("TestPass123", state.passphrase)
        assertEquals("192.168.49.1", state.groupOwnerAddress)
        assertTrue(state.connectedDevices.isEmpty())
    }

    @Test
    fun `WifiDirectState GroupCreated holds connected devices`() {
        val devices = listOf(
            ConnectedDevice(macAddress = "AA:BB:CC:DD:EE:FF", deviceName = "Phone1"),
            ConnectedDevice(macAddress = "11:22:33:44:55:66", deviceName = "Phone2"),
        )
        val state = WifiDirectState.GroupCreated(
            networkName = "DIRECT-FW-Test",
            passphrase = "pass",
            groupOwnerAddress = "192.168.49.1",
            connectedDevices = devices,
        )
        assertEquals(2, state.connectedDevices.size)
        assertEquals("Phone1", state.connectedDevices[0].deviceName)
        assertEquals("AA:BB:CC:DD:EE:FF", state.connectedDevices[0].macAddress)
    }

    @Test
    fun `WifiDirectState Error holds error message`() {
        val state = WifiDirectState.Error("Something went wrong")
        assertEquals("Something went wrong", state.message)
    }

    @Test
    fun `WifiDirectState GroupCreated equality`() {
        val state1 = WifiDirectState.GroupCreated("net", "pass", "192.168.49.1")
        val state2 = WifiDirectState.GroupCreated("net", "pass", "192.168.49.1")
        val state3 = WifiDirectState.GroupCreated("other", "pass", "192.168.49.1")
        assertEquals(state1, state2)
        assertNotEquals(state1, state3)
    }

    @Test
    fun `WifiDirectState Error equality`() {
        val state1 = WifiDirectState.Error("error A")
        val state2 = WifiDirectState.Error("error A")
        val state3 = WifiDirectState.Error("error B")
        assertEquals(state1, state2)
        assertNotEquals(state1, state3)
    }

    // --- Initial state tests ---

    @Test
    fun `initial state is Idle`() = runBlocking {
        val manager = WifiDirectManager()
        val state = manager.state.first()
        assertTrue("Initial state should be Idle", state is WifiDirectState.Idle)
    }

    @Test
    fun `state flow is accessible before start`() {
        val manager = WifiDirectManager()
        val stateFlow = manager.state
        assertTrue("StateFlow should be available", stateFlow.value is WifiDirectState.Idle)
    }

    // --- mapFailureReason coverage ---

    @Test
    fun `mapFailureReason returns different messages for each standard reason code`() {
        val errorMsg = WifiDirectManager.mapFailureReason(WifiP2pManager.ERROR)
        val unsupportedMsg = WifiDirectManager.mapFailureReason(WifiP2pManager.P2P_UNSUPPORTED)
        val busyMsg = WifiDirectManager.mapFailureReason(WifiP2pManager.BUSY)

        assertFalse("ERROR and P2P_UNSUPPORTED should have different messages",
            errorMsg == unsupportedMsg)
        assertFalse("ERROR and BUSY should have different messages",
            errorMsg == busyMsg)
        assertFalse("P2P_UNSUPPORTED and BUSY should have different messages",
            unsupportedMsg == busyMsg)
    }

    @Test
    fun `mapFailureReason includes error code for all unknown values`() {
        for (code in listOf(10, 42, 100, -1)) {
            val msg = WifiDirectManager.mapFailureReason(code)
            assertTrue("Should include error code $code", msg.contains("error code: $code"))
        }
    }

    // --- Passphrase tests ---

    @Test
    fun `DEFAULT_PASSPHRASE is defined and meets minimum length`() {
        assertTrue(
            "Default passphrase should be at least MIN_PASSPHRASE_LENGTH characters",
            WifiDirectManager.DEFAULT_PASSPHRASE.length >= WifiDirectManager.MIN_PASSPHRASE_LENGTH,
        )
    }

    @Test
    fun `MIN_PASSPHRASE_LENGTH is 8 for WPA2`() {
        assertEquals(8, WifiDirectManager.MIN_PASSPHRASE_LENGTH)
    }

    @Test
    fun `generateRandomPassphrase returns a string of correct length`() {
        val passphrase = WifiDirectManager.generateRandomPassphrase()
        assertEquals(12, passphrase.length)
    }

    @Test
    fun `generateRandomPassphrase meets minimum passphrase length`() {
        val passphrase = WifiDirectManager.generateRandomPassphrase()
        assertTrue(
            "Generated passphrase should meet WPA2 minimum length",
            passphrase.length >= WifiDirectManager.MIN_PASSPHRASE_LENGTH,
        )
    }

    @Test
    fun `generateRandomPassphrase produces different values`() {
        val passphrases = (1..10).map { WifiDirectManager.generateRandomPassphrase() }.toSet()
        assertTrue(
            "10 random passphrases should not all be identical",
            passphrases.size > 1,
        )
    }

    @Test
    fun `generateRandomPassphrase contains only alphanumeric characters`() {
        val passphrase = WifiDirectManager.generateRandomPassphrase()
        assertTrue(
            "Generated passphrase should be alphanumeric",
            passphrase.all { it.isLetterOrDigit() },
        )
    }
}
