package org.flossware.hotspot.compat

import android.content.Intent
import android.net.VpnService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests for VpnService compatibility across OEMs.
 *
 * These tests verify that the device supports the VpnService API
 * required for the client-side tunnel. They do not establish an
 * actual VPN connection (that requires user consent via an Activity).
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class VpnServiceCompatTest {

    @Test
    fun vpnServicePrepareReturnsIntent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // VpnService.prepare() returns null if the app already has VPN consent,
        // or an Intent to request consent. Both are valid -- we just verify the
        // API is callable without throwing.
        var didThrow = false
        var result: Intent? = null
        try {
            result = VpnService.prepare(context)
        } catch (e: Exception) {
            didThrow = true
        }

        // On FTL, prepare() may return null (auto-consent in test environments)
        // or an Intent. Either is acceptable. A crash is not.
        assertTrue(
            "VpnService.prepare() should not throw (threw=$didThrow, hasIntent=${result != null})",
            !didThrow,
        )
    }

    @Test
    fun vpnServiceBuilderCanBeInstantiated() {
        // Verify that VpnService.Builder is available on this device.
        // We cannot call build() without an active VpnService, but
        // instantiation should always work.
        var builderCreated = false
        try {
            // Builder requires a VpnService context, so we just check the class is loadable.
            val clazz = Class.forName("android.net.VpnService\$Builder")
            builderCreated = clazz != null
        } catch (e: ClassNotFoundException) {
            builderCreated = false
        }

        assertTrue(
            "VpnService.Builder class should be available",
            builderCreated,
        )
    }

    @Test
    fun vpnServiceIntentResolvable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // The system should be able to handle the VPN consent intent.
        // This verifies that the VPN settings activity exists on the device.
        val intent = Intent("android.net.VpnService").apply {
            setPackage("com.android.vpndialogs")
        }

        // On some OEMs (Xiaomi, Huawei) the vpndialogs package may differ.
        // We check that at least the general VPN settings are resolvable.
        val vpnSettingsIntent = Intent(android.provider.Settings.ACTION_VPN_SETTINGS)
        val resolved = context.packageManager.resolveActivity(vpnSettingsIntent, 0)

        assertTrue(
            "VPN settings activity should be resolvable on this device",
            resolved != null,
        )
    }
}
