package org.flossware.hotspot.client.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiConnectorTest {

    @Test
    fun `initial state is Idle`() {
        val connector = WifiConnector()
        assertTrue(connector.state.value is WifiConnectionState.Idle)
    }

    @Test
    fun `WifiConnectionState Idle identity`() {
        val a = WifiConnectionState.Idle
        val b = WifiConnectionState.Idle
        assertEquals(a, b)
    }

    @Test
    fun `WifiConnectionState Connecting identity`() {
        val a = WifiConnectionState.Connecting
        val b = WifiConnectionState.Connecting
        assertEquals(a, b)
    }

    @Test
    fun `WifiConnectionState Error holds message`() {
        val error = WifiConnectionState.Error("test failure")
        assertEquals("test failure", error.message)
    }

    @Test
    fun `WifiConnectionState Error equality`() {
        val a = WifiConnectionState.Error("msg")
        val b = WifiConnectionState.Error("msg")
        assertEquals(a, b)
    }

    @Test
    fun `WifiConnectionState Error inequality`() {
        val a = WifiConnectionState.Error("msg1")
        val b = WifiConnectionState.Error("msg2")
        assertNotEquals(a, b)
    }

    @Test
    fun `WifiConnectionState ManualRequired identity`() {
        val a = WifiConnectionState.ManualRequired
        val b = WifiConnectionState.ManualRequired
        assertEquals(a, b)
    }

    @Test
    fun `all state types are distinct`() {
        val states = listOf(
            WifiConnectionState.Idle,
            WifiConnectionState.Connecting,
            WifiConnectionState.Error("e"),
            WifiConnectionState.ManualRequired,
        )
        for (i in states.indices) {
            for (j in states.indices) {
                if (i != j) {
                    assertNotEquals(
                        "State $i should not equal state $j",
                        states[i],
                        states[j],
                    )
                }
            }
        }
    }

    @Test
    fun `disconnect resets state to Idle`() {
        val connector = WifiConnector()
        connector.disconnect()
        assertTrue(connector.state.value is WifiConnectionState.Idle)
    }
}
