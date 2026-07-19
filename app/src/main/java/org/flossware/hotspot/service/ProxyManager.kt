package org.flossware.hotspot.service

import timber.log.Timber
import org.flossware.hotspot.model.HotspotState
import org.flossware.hotspot.proxy.DnsRelay
import org.flossware.hotspot.proxy.HttpCache
import org.flossware.hotspot.proxy.Socks5Server
import java.net.DatagramSocket
import java.net.InetAddress
import javax.net.SocketFactory

/**
 * Manages the SOCKS5 proxy servers (group-bound and loopback), DNS relay,
 * and HTTP cache lifecycle.
 *
 * Exposes aggregate stats so the orchestrator can poll without knowing
 * about the individual components.
 */
class ProxyManager {

    private var socksServer: Socks5Server? = null
    private var localSocksServer: Socks5Server? = null
    private var dnsRelay: DnsRelay? = null
    private val httpCache = HttpCache()

    val isRunning: Boolean get() = socksServer != null
    val bytesTransferred: Long get() = socksServer?.bytesTransferred ?: 0
    val dnsCacheHits: Long get() = dnsRelay?.cacheHits ?: 0L
    val httpCacheHits: Long get() = httpCache.hits
    val dataSaved: Long get() = httpCache.dataSaved

    /**
     * Starts the SOCKS5 servers (group-facing and loopback) and the DNS relay.
     *
     * Calling this when already running is a no-op.
     */
    fun start(
        bindAddress: InetAddress,
        socketFactoryProvider: () -> SocketFactory?,
        upstreamDnsProvider: () -> InetAddress,
        socketBinder: (DatagramSocket) -> Unit,
    ) {
        if (socksServer != null) return

        socksServer = Socks5Server(
            bindAddress = bindAddress,
            port = HotspotState.DEFAULT_SOCKS_PORT,
            socketFactoryProvider = socketFactoryProvider,
            httpCache = httpCache,
        ).also { it.start() }

        localSocksServer = Socks5Server(
            bindAddress = InetAddress.getLoopbackAddress(),
            port = HotspotState.DEFAULT_SOCKS_PORT,
            socketFactoryProvider = socketFactoryProvider,
            httpCache = httpCache,
        ).also { it.start() }

        dnsRelay = DnsRelay(
            bindAddress = bindAddress,
            listenPort = HotspotState.DEFAULT_DNS_PORT,
            upstreamDnsProvider = upstreamDnsProvider,
            socketBinder = socketBinder,
        ).also { it.start() }
    }

    /**
     * Called when the mobile network is lost. The socketFactoryProvider will
     * already return null (causing new connections to fail immediately), but
     * this method logs the event for observability.
     */
    fun notifyNetworkLost() {
        Timber.tag(TAG).w("Mobile network lost -- new proxy connections will fail until network returns")
    }

    /** Stops all proxy components and clears the HTTP cache. */
    fun stop() {
        dnsRelay?.stop()
        dnsRelay = null
        localSocksServer?.stop()
        localSocksServer = null
        socksServer?.stop()
        socksServer = null
        httpCache.clear()
    }

    companion object {
        private const val TAG = "ProxyManager"
    }
}
