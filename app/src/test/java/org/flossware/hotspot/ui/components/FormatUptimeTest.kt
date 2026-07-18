package org.flossware.hotspot.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatUptimeTest {

    @Test
    fun `zero seconds`() {
        assertEquals("0:00", formatUptime(0))
    }

    @Test
    fun `seconds only`() {
        assertEquals("0:45", formatUptime(45))
    }

    @Test
    fun `minutes and seconds`() {
        assertEquals("5:30", formatUptime(330))
    }

    @Test
    fun `hours minutes seconds`() {
        assertEquals("1:00:00", formatUptime(3600))
    }

    @Test
    fun `hours with minutes and seconds`() {
        assertEquals("2:30:15", formatUptime(9015))
    }

    @Test
    fun `large uptime`() {
        assertEquals("24:00:00", formatUptime(86400))
    }
}
