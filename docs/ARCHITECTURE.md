# Architecture

This document describes the architecture of FlossWare Hotspot, the design decisions behind it, and the rationale for each choice.

## Overview

FlossWare Hotspot is a two-app system that enables Android-to-Android internet sharing without carrier tethering plans. It uses Wi-Fi Direct for the data link and SOCKS5 proxying for transparent traffic forwarding.

```
┌─────────────────────────────────────────────────────────────────┐
│                        HOST PHONE                               │
│                                                                 │
│  ┌─────────────┐  ┌──────────────────┐  ┌───────────────────┐  │
│  │ WifiDirect  │  │  Socks5Server    │  │   DnsRelay        │  │
│  │ Manager     │  │  :1080           │  │   :5353           │  │
│  │             │  │                  │  │                   │  │
│  │ Creates P2P │  │  RFC 1928        │  │  Forwards DNS     │  │
│  │ group as    │  │  CONNECT only    │  │  over cellular    │  │
│  │ Group Owner │  │  Server-side DNS │  │  with caching     │  │
│  └──────┬──────┘  └────────┬─────────┘  └────────┬──────────┘  │
│         │                  │                      │             │
│         │           ┌──────┴──────┐               │             │
│         │           │  HttpCache  │               │             │
│         │           │  (port 80)  │               │             │
│         │           └──────┬──────┘               │             │
│         │                  │                      │             │
│  ┌──────┴──────────────────┴──────────────────────┴──────────┐  │
│  │                   HotspotService                          │  │
│  │              Foreground service coordinator               │  │
│  │                                                           │  │
│  │  - Registers cellular network callback                    │  │
│  │  - Binds outbound sockets to mobile data                  │  │
│  │  - Monitors DNS servers from link properties              │  │
│  │  - Periodic stats refresh (2s interval)                   │  │
│  └───────────────────────┬───────────────────────────────────┘  │
│                          │                                      │
│                 cellular-bound sockets                          │
│                          │                                      │
│                    ┌─────┴─────┐                                │
│                    │  Internet │                                │
│                    └───────────┘                                │
└─────────────────────────────────────────────────────────────────┘
         ▲ P2P Wi-Fi (192.168.49.x)    ▲ Bluetooth RFCOMM
         │                              │
         │    ┌─────────────────────────┘
         │    │
┌────────┴────┴───────────────────────────────────────────────────┐
│                       CLIENT DEVICE                             │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                  TunnelService (VpnService)              │   │
│  │                                                          │   │
│  │  - Creates TUN interface (10.0.0.2/32 + fd00::2/128)    │   │
│  │  - Routes 0.0.0.0/0 and ::/0 through TUN                │   │
│  │  - Excludes own package (addDisallowedApplication)       │   │
│  │  - DNS server: 198.18.0.2 (virtual, handled by mapdns)  │   │
│  └──────────────────────┬───────────────────────────────────┘   │
│                         │                                       │
│  ┌──────────────────────┴───────────────────────────────────┐   │
│  │                    SocksTunnel                            │   │
│  │                                                          │   │
│  │  - Generates YAML config for hev-socks5-tunnel           │   │
│  │  - Manages native library lifecycle (start/stop)         │   │
│  │  - Collects traffic statistics via JNI                    │   │
│  └──────────────────────┬───────────────────────────────────┘   │
│                         │ JNI                                   │
│  ┌──────────────────────┴───────────────────────────────────┐   │
│  │              hev-socks5-tunnel (native C)                │   │
│  │                                                          │   │
│  │  - TProxyService.kt loads libhev-socks5-tunnel.so        │   │
│  │  - Full userspace TCP/IP stack (lwIP)                     │   │
│  │  - Reads packets from TUN fd                              │   │
│  │  - Forwards through SOCKS5 to host                        │   │
│  │  - mapdns: 198.18.0.2 -> real DNS via SOCKS5 tunnel      │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

## Data flow

### TCP connection (e.g., loading a webpage)

```
App on client device
    |
    v
[1] TUN interface captures packet (VpnService routes 0.0.0.0/0)
    |
    v
[2] hev-socks5-tunnel reads raw IP packet from TUN fd
    |
    v
[3] lwIP reconstructs TCP stream in userspace
    |
    v
[4] SOCKS5 CONNECT to host at 192.168.49.1:1080
    |  (over Wi-Fi Direct P2P link)
    v
[5] Socks5Server on host receives CONNECT request
    |
    v
[6] Server resolves hostname via dnsResolver (cellular DNS)
    |
    v
[7] Server creates upstream socket bound to mobile network
    |  (via Network.socketFactory from ConnectivityManager)
    v
[8] Bidirectional relay: client <-> SOCKS5 <-> Internet
```

### DNS resolution

DNS is handled through two complementary mechanisms:

1. **mapdns (client-side):** hev-socks5-tunnel intercepts DNS queries sent to the virtual address `198.18.0.2` and tunnels them through the SOCKS5 connection. The tunnel rewrites DNS queries to use addresses in the `100.64.0.0/10` range (CGNAT space) and maintains a mapping table (cache-size: 10000 entries).

2. **DnsRelay (host-side):** A UDP forwarder on port 5353 that accepts DNS queries from clients over the P2P interface and forwards them to the carrier's DNS servers (discovered via `LinkProperties.dnsServers`). Includes a response cache with TTL-based expiration (10s-3600s, max 1000 entries).

### Bluetooth transport (alternative path)

```
Client device                         Host phone
┌──────────────┐                      ┌──────────────────┐
│ Bluetooth    │  RFCOMM connection   │ BluetoothServer  │
│ Tunnel       │─────────────────────>│                  │
│              │                      │ Bridges to       │
│ Opens local  │                      │ localhost:1080   │
│ ServerSocket │                      │ (local SOCKS5)   │
│ on 127.0.0.1 │                      └──────────────────┘
└──────┬───────┘
       │
       v
  TunnelService connects to
  127.0.0.1:<localPort>
  (same VPN + SocksTunnel flow)
```

The Bluetooth transport is a bridge: the client opens a local TCP server, the VPN tunnel connects to it, and traffic is relayed over Bluetooth RFCOMM to the host's Bluetooth server, which forwards it to the local SOCKS5 server. This adds latency but works when Wi-Fi Direct is unavailable.

## Component details

### Socks5Server

- Implements SOCKS5 (RFC 1928) CONNECT command only
- No authentication (AUTH_NONE, `0x00`)
- Supports IPv4, IPv6, and domain name address types
- Server-side DNS resolution: the client sends domain names, and the server resolves them using the cellular network's DNS
- Thread pool: 4 core, 32 max, 60s keepalive, 64-entry queue with CallerRunsPolicy
- Bidirectional relay with 8KB buffers
- Optional HTTP response caching for port 80 connections (plaintext only)
- Tracks total bytes transferred via AtomicLong

### DnsRelay

- UDP DNS forwarder binding to the P2P interface address
- Listens on port 5353 (not 53, to avoid conflicts)
- Forwards queries to the carrier's DNS server discovered via `ConnectivityManager.NetworkCallback.onLinkPropertiesChanged`
- Falls back to `8.8.8.8` if carrier DNS is unavailable
- Response cache: ConcurrentHashMap keyed on question section (excluding transaction ID)
- TTL extraction from answer records, clamped to 10s-3600s range
- Max 1000 cached entries; evicts expired first, then oldest
- Thread pool: 4 core, 8 max for concurrent query forwarding

### SocksTunnel + TProxyService (JNI bridge)

- `TProxyService.kt` loads `libhev-socks5-tunnel.so` via `System.loadLibrary`
- Three JNI methods: `TProxyStartService(configPath, fd)`, `TProxyStopService()`, `TProxyGetStats()`
- `SocksTunnel` generates a YAML configuration file at runtime with tunnel MTU, SOCKS5 address/port, mapdns settings, and timeout values
- The native library runs in its own thread(s), reading/writing the TUN file descriptor directly

### WifiDirectManager

- Creates a Wi-Fi Direct P2P group with the phone as Group Owner
- On Android 10+ (API 29): uses `WifiP2pConfig.Builder` to set a deterministic network name (`DIRECT-FW-FlossHotspot`) and passphrase
- On Android 8-9: falls back to system-generated random name/passphrase
- Group Owner address is always `192.168.49.1` (Android Wi-Fi Direct convention)
- Monitors group state and connected client list via `WifiP2pManager.requestGroupInfo`

### HotspotService

- Android foreground service that orchestrates all host-side components
- Lifecycle: registers a `ConnectivityManager.NetworkCallback` for `TRANSPORT_CELLULAR` to track the mobile data network
- Provides `Network.socketFactory` to `Socks5Server` so outbound connections are bound to cellular (never the P2P interface)
- Starts two SOCKS5 server instances: one on the P2P address (for Wi-Fi Direct clients) and one on loopback (for Bluetooth clients)
- Periodic stats collection every 2 seconds (bytes transferred, DNS cache hits, HTTP cache hits)
- Notification with stop action

## Architecture decision records

### ADR-1: Two separate apps instead of one

**Context:** The system needs code on both the sharing phone and the connecting device.

**Decision:** Ship two separate APKs (host and client) rather than a single app with a mode selector.

**Rationale:**
- Smaller install size -- each device only needs the relevant code
- The client includes a native C library (~300KB per ABI, ~1.2MB total) that host-only users do not need
- Clearer permission model -- client needs VPN permission, host needs location/Wi-Fi Direct
- Simpler UI -- each app has a single screen with a single primary action

### ADR-2: SOCKS5 over HTTP proxy

**Context:** Need a proxy protocol that works for all TCP traffic, not just HTTP.

**Decision:** SOCKS5 (RFC 1928) with CONNECT command and server-side DNS resolution.

**Rationale:**
- HTTP proxies only handle HTTP/HTTPS CONNECT; SOCKS5 handles arbitrary TCP connections
- Server-side DNS resolution avoids DNS leaks on the client side
- SOCKS5 is well-understood with mature client libraries
- hev-socks5-tunnel provides a battle-tested native client implementation

**Trade-offs:**
- No UDP ASSOCIATE support -- UDP-heavy apps (VoIP, some games) will not work through the proxy. DNS is handled separately via mapdns and DnsRelay.
- No authentication -- acceptable for a local P2P network but would need to be added for any internet-exposed deployment

### ADR-3: Native tun2socks via hev-socks5-tunnel

**Context:** Need to capture all device traffic and tunnel it through SOCKS5.

**Decision:** Use [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel), a native C library with its own userspace TCP/IP stack (lwIP), loaded via JNI.

**Rationale:**
- Pure-Java tun2socks implementations exist but are significantly slower and less reliable
- hev-socks5-tunnel is actively maintained, small (~300KB per ABI), and handles edge cases in TCP state management
- Includes mapdns for transparent DNS rewriting without a separate DNS proxy on the client
- JNI interface is minimal (3 methods) -- low integration surface area

**Trade-offs:**
- Adds NDK build dependency (NDK 27.0.12077973 required)
- Git submodule with nested dependencies (lwIP, libyaml, hev-task-system) makes cloning more complex
- Native crash debugging is harder than pure Kotlin

### ADR-4: Wi-Fi Direct instead of Wi-Fi hotspot API

**Context:** Need to create a local wireless network for client devices to connect to.

**Decision:** Use Wi-Fi Direct (P2P) Group Owner mode instead of the Android hotspot/tethering API.

**Rationale:**
- The Android tethering API (`WifiManager.startLocalOnlyHotspot` or `TetheringManager`) is restricted by carriers and OEMs
- Wi-Fi Direct creates a peer-to-peer network that is not subject to tethering restrictions
- The Group Owner address is deterministic (`192.168.49.1`), simplifying proxy configuration
- No root required

**Trade-offs:**
- Requires location permission (Android's Wi-Fi Direct API mandate, API 26-32)
- Network name is random on Android < 10; only API 29+ supports custom names
- Some devices have buggy Wi-Fi Direct implementations

### ADR-5: VpnService for transparent traffic capture

**Context:** All apps on the client device need their traffic routed through the SOCKS5 tunnel without per-app configuration.

**Decision:** Use Android's `VpnService` to create a TUN interface and route all traffic (0.0.0.0/0, ::/0) through it.

**Rationale:**
- VpnService is the only non-root way to capture all device traffic on Android
- `addDisallowedApplication(packageName)` prevents routing loops -- the client app's own SOCKS5 connections bypass the TUN. Same pattern as WireGuard for Android.
- Users see a system-level VPN indicator (key icon), providing transparency

**Trade-offs:**
- Only one VPN can be active at a time on Android -- cannot coexist with other VPN apps
- VPN consent dialog is shown once per app install (system requirement)

### ADR-6: Cellular-bound sockets

**Context:** The host phone has two active network interfaces: cellular and Wi-Fi Direct P2P. Outbound traffic must go through cellular, not loop back through P2P.

**Decision:** Use `ConnectivityManager.NetworkCallback` to obtain the cellular `Network` object and use `Network.socketFactory` for all outbound connections in `Socks5Server`.

**Rationale:**
- Android's default routing may prefer the Wi-Fi Direct interface for outbound traffic
- Binding sockets to the cellular network guarantees traffic exits through mobile data
- The carrier sees outbound connections from the phone's own process, not from a tethered device -- TTL-based detection is inherently bypassed

### ADR-7: Thread pool sizing

**Context:** The SOCKS5 server and DNS relay both need concurrent request handling.

**Decision:** SOCKS5 uses a ThreadPoolExecutor with 4 core threads, 32 max threads, 60s keepalive, and a 64-entry bounded queue with CallerRunsPolicy. DNS uses 4 core threads, 8 max threads with the same overflow policy.

**Rationale:**
- The SOCKS5 server needs more threads because each TCP connection occupies two threads (bidirectional relay) for the duration of the connection, which can be long-lived
- 32 max threads supports approximately 16 concurrent TCP connections, which is sufficient for phone-to-phone use
- CallerRunsPolicy provides natural backpressure -- when all threads and the queue are full, the accept thread handles the connection itself, slowing down new connection acceptance
- The DNS relay uses fewer threads (8 max) because DNS queries are short-lived (5s timeout) and the cache reduces upstream queries
- 60-second keepalive for idle threads avoids wasting resources when the proxy is idle

### ADR-8: Bluetooth transport bridge

**Context:** Wi-Fi Direct is not always available or reliable on all devices.

**Decision:** Add Bluetooth RFCOMM as an alternative transport. The host runs a `BluetoothServer` that bridges RFCOMM connections to the local SOCKS5 server; the client runs a `BluetoothTunnel` that creates a local TCP server and relays traffic over RFCOMM.

**Rationale:**
- Bluetooth is universally available on Android devices
- RFCOMM provides a reliable stream transport suitable for proxying SOCKS5
- The bridge pattern means the existing SOCKS5 and VPN code is reused without modification

**Trade-offs:**
- Bluetooth bandwidth is significantly lower than Wi-Fi Direct (~2 Mbps vs ~250 Mbps)
- Higher latency due to additional relay hops (VPN -> local TCP -> RFCOMM -> host TCP -> SOCKS5)
- Suitable for browsing and messaging but not for streaming or large downloads