package org.flossware.hotspot.client.tunnel

import timber.log.Timber
import hev.htproxy.TProxyService
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class SocksTunnel(
    private val tunFd: Int,
    private val socksHost: String,
    private val socksPort: Int,
    private val cacheDir: File,
    private val ipv6Enabled: Boolean = false,
) {
    private val tproxy = TProxyService()
    private val running = AtomicBoolean(false)

    fun start() {
        if (running.getAndSet(true)) return
        val configFile = File(cacheDir, CONFIG_FILENAME)
        configFile.writeText(buildConfig(socksHost, socksPort, ipv6Enabled))
        tproxy.TProxyStartService(configFile.absolutePath, tunFd)
        Timber.tag(TAG).i(
            "transport_connect event=native_tunnel_started host=%s port=%d ipv6=%b",
            socksHost, socksPort, ipv6Enabled,
        )
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        tproxy.TProxyStopService()
        Timber.tag(TAG).i("transport_disconnect event=native_tunnel_stopped")
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

        /** IPv6 minimum MTU per RFC 8200. */
        internal const val IPV6_MIN_MTU = 1280
        /** Default MTU for IPv4-only mode. */
        internal const val IPV4_DEFAULT_MTU = 1500

        internal fun buildConfig(
            socksHost: String,
            socksPort: Int,
            ipv6Enabled: Boolean = false,
        ): String {
            val mtu = if (ipv6Enabled) IPV6_MIN_MTU else IPV4_DEFAULT_MTU
            return """
                tunnel:
                  mtu: $mtu
                socks5:
                  port: $socksPort
                  address: $socksHost
                dns:
                  address: $socksHost
                  port: 5353
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
}
