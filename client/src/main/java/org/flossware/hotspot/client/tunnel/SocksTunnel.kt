package org.flossware.hotspot.client.tunnel

import android.util.Log
import hev.htproxy.TProxyService
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class SocksTunnel(
    private val tunFd: Int,
    private val socksHost: String,
    private val socksPort: Int,
    private val cacheDir: File,
) {
    private val tproxy = TProxyService()
    private val running = AtomicBoolean(false)

    fun start() {
        if (running.getAndSet(true)) return
        val configFile = File(cacheDir, CONFIG_FILENAME)
        configFile.writeText(buildConfig(socksHost, socksPort))
        tproxy.TProxyStartService(configFile.absolutePath, tunFd)
        Log.i(TAG, "Native tunnel started -> $socksHost:$socksPort")
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        tproxy.TProxyStopService()
        Log.i(TAG, "Native tunnel stopped")
    }

    val isRunning: Boolean get() = running.get()

    val stats: TrafficStats
        get() {
            val s = tproxy.TProxyGetStats()
            return TrafficStats(s[0], s[1], s[2], s[3])
        }

    data class TrafficStats(
        val txPackets: Long,
        val txBytes: Long,
        val rxPackets: Long,
        val rxBytes: Long,
    )

    companion object {
        private const val TAG = "SocksTunnel"
        internal const val CONFIG_FILENAME = "tun2socks.yml"

        internal const val DNS_ADDRESS = "198.18.0.2"

        internal fun buildConfig(socksHost: String, socksPort: Int): String = """
            tunnel:
              mtu: 1500
            socks5:
              port: $socksPort
              address: $socksHost
            mapdns:
              address: $DNS_ADDRESS
              port: 53
              network: 100.64.0.0
              netmask: 255.192.0.0
              cache-size: 10000
            misc:
              log-level: warn
              connect-timeout: 5000
              read-write-timeout: 60000
              limit-nofile: 65535
        """.trimIndent()
    }
}
