package org.flossware.hotspot.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogExporterTest {

    @Test
    fun `sanitize strips password values`() {
        val input = "Config password=mySecret123 loaded"
        val result = LogExporter.sanitize(input)
        assertFalse(result.contains("mySecret123"))
        assertTrue(result.contains("password=***"))
    }

    @Test
    fun `sanitize strips passphrase values`() {
        val input = "Network passphrase=SuperSecret loaded"
        val result = LogExporter.sanitize(input)
        assertFalse(result.contains("SuperSecret"))
        assertTrue(result.contains("passphrase=***"))
    }

    @Test
    fun `sanitize strips WiFi QR password field`() {
        val input = "WIFI:T:WPA;S:MyNetwork;P:myWifiPass;;"
        val result = LogExporter.sanitize(input)
        assertFalse(result.contains("myWifiPass"))
        assertTrue(result.contains("P:***"))
    }

    @Test
    fun `sanitize strips username in quotes`() {
        val input = "Authentication succeeded for user 'admin'"
        val result = LogExporter.sanitize(input)
        assertFalse(result.contains("admin"))
        assertTrue(result.contains("'***'"))
    }

    @Test
    fun `sanitize strips username with equals`() {
        val input = "Config username=john loaded"
        val result = LogExporter.sanitize(input)
        assertFalse(result.contains("john"))
        assertTrue(result.contains("username=***"))
    }

    @Test
    fun `sanitize strips password with colon separator`() {
        val input = "password: secret123"
        val result = LogExporter.sanitize(input)
        assertFalse(result.contains("secret123"))
        assertTrue(result.contains("password"))
    }

    @Test
    fun `sanitize strips credential values`() {
        val input = "credential=abc123 used"
        val result = LogExporter.sanitize(input)
        assertFalse(result.contains("abc123"))
        assertTrue(result.contains("credential=***"))
    }

    @Test
    fun `sanitize strips secret values`() {
        val input = "secret=topSecret loaded"
        val result = LogExporter.sanitize(input)
        assertFalse(result.contains("topSecret"))
        assertTrue(result.contains("secret=***"))
    }

    @Test
    fun `sanitize strips token values`() {
        val input = "token=abc123def456 used"
        val result = LogExporter.sanitize(input)
        assertFalse(result.contains("abc123def456"))
        assertTrue(result.contains("token=***"))
    }

    @Test
    fun `sanitize strips api key values`() {
        val input = "api_key=sk-abc123 loaded"
        val result = LogExporter.sanitize(input)
        assertFalse(result.contains("sk-abc123"))
        assertTrue(result.contains("api_key=***"))
    }

    @Test
    fun `sanitize strips auth succeeded messages`() {
        val input = "Authentication succeeded for user 'testuser'"
        val result = LogExporter.sanitize(input)
        assertFalse(result.contains("testuser"))
        assertTrue(result.contains("for user '***'"))
    }

    @Test
    fun `sanitize strips auth failed messages`() {
        val input = "Authentication failed for user 'baduser'"
        val result = LogExporter.sanitize(input)
        assertFalse(result.contains("baduser"))
        assertTrue(result.contains("for user '***'"))
    }

    @Test
    fun `sanitize is case insensitive`() {
        val input = "PASSWORD=secret123 and Password=other456"
        val result = LogExporter.sanitize(input)
        assertFalse(result.contains("secret123"))
        assertFalse(result.contains("other456"))
    }

    @Test
    fun `sanitize leaves non-sensitive data unchanged`() {
        val input = "SOCKS5 listening on 192.168.49.1:1080 (no auth)"
        val result = LogExporter.sanitize(input)
        assertEquals(input, result)
    }

    @Test
    fun `sanitize leaves normal log messages unchanged`() {
        val input = "Connection accepted from 192.168.49.2 (client: 1, total: 1)"
        val result = LogExporter.sanitize(input)
        assertEquals(input, result)
    }

    @Test
    fun `sanitize handles multiple sensitive values in one line`() {
        val input = "Config password=secret username=admin"
        val result = LogExporter.sanitize(input)
        assertFalse(result.contains("secret"))
        assertFalse(result.contains("admin"))
    }

    @Test
    fun `sanitize strips psk values`() {
        val input = "PSK=wifipassword123"
        val result = LogExporter.sanitize(input)
        assertFalse(result.contains("wifipassword123"))
    }
}
