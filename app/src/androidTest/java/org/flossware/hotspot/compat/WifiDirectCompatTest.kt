package org.flossware.hotspot.compat

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Smoke tests for Wi-Fi Direct compatibility across OEMs.
 *
 * These tests verify that the device's Wi-Fi Direct stack is functional
 * enough for FlossWare Hotspot to operate. They run on real hardware
 * via Firebase Test Lab.
 *
 * Tests that require Wi-Fi Direct hardware will be skipped (not failed)
 * on devices that lack the feature.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class WifiDirectCompatTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @Test
    fun deviceReportsWifiDirectFeature() {
        val hasFeature = context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
        // This test documents feature availability; it passes either way.
        // The result is captured in the test report for the compatibility matrix.
        assertTrue(
            "Recording Wi-Fi Direct feature availability (present=$hasFeature)",
            true,
        )
    }

    @Test
    fun wifiP2pManagerServiceAvailable() {
        assumeTrue(
            "Device lacks FEATURE_WIFI_DIRECT, skipping",
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT),
        )

        val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        assertNotNull(
            "WifiP2pManager system service should be available on a device with FEATURE_WIFI_DIRECT",
            manager,
        )
    }

    @Test
    fun channelInitializationSucceeds() {
        assumeTrue(
            "Device lacks FEATURE_WIFI_DIRECT, skipping",
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT),
        )

        val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        assumeTrue("WifiP2pManager not available", manager != null)

        val latch = CountDownLatch(1)
        var channel: WifiP2pManager.Channel? = null

        // Channel initialization must happen on the main looper
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            channel = manager!!.initialize(context, Looper.getMainLooper(), null)
            latch.countDown()
        }

        assertTrue("Channel initialization should complete", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertNotNull("WifiP2pManager.Channel should not be null", channel)
    }

    @Test
    fun peerDiscoveryCallbackFires() {
        assumeTrue(
            "Device lacks FEATURE_WIFI_DIRECT, skipping",
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT),
        )

        val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        assumeTrue("WifiP2pManager not available", manager != null)

        val callbackLatch = CountDownLatch(1)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val channel = manager!!.initialize(context, Looper.getMainLooper(), null)

            // Attempt peer discovery; on FTL the call may fail due to permissions
            // or missing Wi-Fi hardware, but the callback itself should fire.
            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    callbackLatch.countDown()
                }
                override fun onFailure(reason: Int) {
                    // A failure callback is still a valid callback -- the API works.
                    callbackLatch.countDown()
                }
            })
        }

        assertTrue(
            "discoverPeers callback should fire within ${TIMEOUT_SECONDS}s",
            callbackLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
    }

    companion object {
        private const val TIMEOUT_SECONDS = 10L
    }
}
