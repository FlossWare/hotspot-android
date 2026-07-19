package org.flossware.hotspot.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.flossware.hotspot.transport.PeerIdentity
import org.flossware.hotspot.transport.TransportType

/**
 * [ServiceDiscovery] implementation using Android's NSD (Network Service
 * Discovery) API for mDNS / DNS-SD based peer discovery.
 *
 * **Registration:** Advertises a `_flossware._tcp` service with TXT records
 * containing the public key fingerprint, protocol version, and available
 * transports.
 *
 * **Discovery:** Resolves `_flossware._tcp` services on the local network
 * and emits [DiscoveryResult]s for each one found.
 */
class NsdDiscovery(private val context: Context) : ServiceDiscovery {

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    override fun discover(): Flow<DiscoveryResult> = callbackFlow {
        val mgr = getNsdManager()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "NSD discovery started for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "NSD service found: ${serviceInfo.serviceName}")
                @Suppress("DEPRECATION")
                mgr.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "NSD resolve failed for ${info.serviceName}: error $errorCode")
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val result = serviceInfoToResult(info) ?: return
                        trySend(result)
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "NSD service lost: ${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "NSD discovery stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "NSD start discovery failed: error $errorCode")
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "NSD stop discovery failed: error $errorCode")
            }
        }

        discoveryListener = listener
        mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)

        awaitClose {
            try {
                mgr.stopServiceDiscovery(listener)
            } catch (e: IllegalArgumentException) {
                Log.d(TAG, "Discovery listener already unregistered: ${e.message}")
            }
            discoveryListener = null
        }
    }

    override fun register(
        fingerprint: String,
        transports: Set<TransportType>,
        port: Int,
    ) {
        val mgr = getNsdManager()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute(TXT_KEY_FINGERPRINT, fingerprint)
            setAttribute(TXT_KEY_VERSION, PROTOCOL_VERSION.toString())
            setAttribute(TXT_KEY_TRANSPORTS, transports.joinToString(",") { it.name })
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "NSD service registered: ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD registration failed: error $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "NSD service unregistered: ${info.serviceName}")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD unregistration failed: error $errorCode")
            }
        }

        registrationListener = listener
        mgr.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    override fun unregister() {
        registrationListener?.let { listener ->
            try {
                getNsdManager().unregisterService(listener)
            } catch (e: IllegalArgumentException) {
                Log.d(TAG, "Registration listener already unregistered: ${e.message}")
            }
        }
        registrationListener = null
    }

    private fun getNsdManager(): NsdManager {
        return nsdManager ?: (context.getSystemService(Context.NSD_SERVICE) as NsdManager).also {
            nsdManager = it
        }
    }

    companion object {
        private const val TAG = "NsdDiscovery"

        /** mDNS service type for FlossWare peer discovery. */
        const val SERVICE_TYPE = "_flossware._tcp."
        const val SERVICE_NAME = "FlossHotspot"

        internal const val TXT_KEY_FINGERPRINT = "fp"
        internal const val TXT_KEY_VERSION = "v"
        internal const val TXT_KEY_TRANSPORTS = "tr"
        internal const val PROTOCOL_VERSION = 1

        /**
         * Converts a resolved [NsdServiceInfo] into a [DiscoveryResult],
         * or `null` if the required fields are missing.
         */
        internal fun serviceInfoToResult(info: NsdServiceInfo): DiscoveryResult? {
            @Suppress("DEPRECATION")
            val host = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                info.hostAddresses.firstOrNull()
            } else {
                info.host
            } ?: return null
            val port = info.port

            val fingerprint = info.attributes[TXT_KEY_FINGERPRINT]
                ?.let { String(it) }
                ?: ""

            val versionStr = info.attributes[TXT_KEY_VERSION]
                ?.let { String(it) }
            val version = versionStr?.toIntOrNull() ?: PROTOCOL_VERSION

            val transportsStr = info.attributes[TXT_KEY_TRANSPORTS]
                ?.let { String(it) }
            val transports = parseTransports(transportsStr)

            val hostAddress = host.hostAddress ?: return null

            val endpoints = buildMap {
                if (TransportType.WIFI_DIRECT in transports) {
                    put(TransportType.WIFI_DIRECT, "$hostAddress:$port")
                }
            }

            val peer = PeerIdentity(
                publicKeyFingerprint = fingerprint,
                label = info.serviceName ?: "Unknown",
                endpoints = endpoints,
                protocolVersion = version,
            )

            return DiscoveryResult(
                peerIdentity = peer,
                availableTransports = transports,
            )
        }

        /**
         * Parses a comma-separated transport string (e.g. "WIFI_DIRECT,BLUETOOTH")
         * into a set of [TransportType] values, ignoring unrecognised entries.
         */
        internal fun parseTransports(raw: String?): Set<TransportType> {
            if (raw.isNullOrBlank()) return emptySet()
            return raw.split(",").mapNotNull { name ->
                try {
                    TransportType.valueOf(name.trim())
                } catch (_: IllegalArgumentException) {
                    null
                }
            }.toSet()
        }
    }
}
