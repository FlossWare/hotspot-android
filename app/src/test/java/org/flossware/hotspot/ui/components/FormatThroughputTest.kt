package org.flossware.hotspot.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatThroughputTest {

    @Test
    fun `sub-kbps shows bps`() {
        assertEquals("500.0 bps", formatThroughput(0.5f))
    }

    @Test
    fun `zero shows bps`() {
        assertEquals("0.0 bps", formatThroughput(0f))
    }

    @Test
    fun `kbps range`() {
        assertEquals("150.0 kbps", formatThroughput(150f))
    }

    @Test
    fun `mbps range`() {
        assertEquals("5.0 Mbps", formatThroughput(5000f))
    }

    @Test
    fun `boundary at 1 kbps`() {
        assertEquals("1.0 kbps", formatThroughput(1f))
    }

    @Test
    fun `boundary at 1000 kbps`() {
        assertEquals("1.0 Mbps", formatThroughput(1000f))
    }
}
