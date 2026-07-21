# FlossWare Hotspot

Free Android app that shares your phone's mobile data via Wi-Fi -- bypasses carrier hotspot restrictions without root. All apps work transparently.


[![Android CI](https://github.com/FlossWare/hotspot-android/actions/workflows/android.yml/badge.svg)](https://github.com/FlossWare/hotspot-android/actions/workflows/android.yml)

## The Problem

Carriers block the built-in Android hotspot unless you pay for a tethering add-on. You already pay for the data -- this app lets you use it.

## How It Works

Two apps work together -- a **Host** on the phone sharing data, and a **Client** on the connecting device:

```
HOST PHONE                               CLIENT DEVICE
┌──────────────────────┐                 ┌──────────────────────┐
│  Wi-Fi Direct Group  │   P2P Wi-Fi     │                      │
│  Owner               │◄───────────────│  Connect to SSID     │
│                      │                 │                      │
│  Socks5Server :1080  │◄── SOCKS5 ─────│  VpnService + TUN    │
│                      │    (TCP+UDP)    │                      │
│  DnsRelay :5353      │◄── DNS ────────│  hev-socks5-tunnel   │
│                      │                 │  (native tun2socks)  │
│                      │                 │                      │
│  Cellular Data ──────┼──► Internet     │  All app traffic     │
│                      │                 │  captured by VPN     │
└──────────────────────┘                 └──────────────────────┘
```

**Wi-Fi Direct** creates a peer-to-peer Wi-Fi network -- not carrier tethering. The phone becomes a Group Owner that other devices connect to like a regular Wi-Fi access point.

**SOCKS5 Server** runs on the host phone and forwards connections through mobile data. The carrier sees normal app traffic from the phone's own process -- TTL-based tethering detection is inherently bypassed.

**VPN Client** runs on the connecting device, captures ALL traffic via Android's VpnService, and tunnels it through SOCKS5 using [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) -- a native C library with a full userspace TCP/IP stack (lwIP). Every app works: browsers, social media, streaming, games, everything. TCP and UDP.

**DNS Relay** forwards hostname lookups through mobile data so connected devices can resolve addresses.

**Bluetooth Transport** provides an alternative connectivity path when Wi-Fi Direct is unavailable -- the client connects via Bluetooth RFCOMM and the host bridges traffic to the local SOCKS5 server.

## Requirements

- Android 8.0+ (API 26) on both devices
- Active mobile data connection on host phone
- No root required

## Quick Start

### Host phone (sharing data)

1. Install **FlossWare Hotspot** (host APK)
2. Grant permissions (location for Wi-Fi Direct)
3. Tap **Start Hotspot**
4. Share the displayed Wi-Fi name and password

### Client device (connecting)

1. Install **FlossWare Hotspot Client** (client APK)
2. Connect to the displayed Wi-Fi network
3. Open FlossWare Hotspot Client
4. Tap **Connect**

All traffic is now routed through the host's mobile data.

### Manual mode (no client app)

Apps that support SOCKS5 proxies can connect directly -- configure `192.168.49.1:1080` as the SOCKS5 server (e.g., Firefox: Settings > Network > SOCKS5).

## Building

### Prerequisites

| Requirement | Version | Notes |
|-------------|---------|-------|
| JDK | 21 | Temurin recommended |
| Android SDK | API 35 (compileSdk) | via Android Studio or `sdkmanager` |
| Android NDK | **27.0.12077973** | Must be this exact version (see below) |
| Git | any | Submodule support required |

The NDK is required because the client module includes [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel), a native C library compiled via `ndk-build` for four ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`.

### Installing the NDK

**Android Studio:** Settings > Languages & Frameworks > Android SDK > SDK Tools > check "Show Package Details" > NDK (Side by side) > select **27.0.12077973**.

**Command line:**
```bash
sdkmanager "ndk;27.0.12077973"
```

### Build steps

```bash
# Clone with submodules (required -- the client depends on hev-socks5-tunnel)
git clone --recursive https://github.com/FlossWare/hotspot-android.git
cd hotspot-android

# If you already cloned without --recursive:
git submodule update --init --recursive

# Build
./gradlew assembleDebug
```

### APK outputs

- Host: `app/build/outputs/apk/debug/app-debug.apk`
- Client: `client/build/outputs/apk/debug/client-debug.apk`

### Running tests

```bash
./gradlew test
```

### Build troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `No version of NDK matched the requested version` | NDK 27.0.12077973 not installed | `sdkmanager "ndk;27.0.12077973"` |
| `Android.mk: No such file or directory` | Submodule not initialized | `git submodule update --init --recursive` |
| Empty `client/src/main/jni/hev-socks5-tunnel/` directory | Cloned without `--recursive` | `git submodule update --init --recursive` |
| `CMake` or `ninja` errors during native build | Wrong build system assumption | This project uses `ndk-build` (Android.mk), not CMake |
| `hev-socks5-tunnel` build failures on macOS | Missing make/gcc toolchain | Install Xcode command line tools: `xcode-select --install` |
| JDK version mismatch | Wrong JDK version | Requires JDK 17+ (project targets Java 17 compatibility) |

### CI considerations

The CI workflows (`.github/workflows/android.yml`) handle the native build automatically:
- Submodules are checked out with `submodules: recursive`
- The NDK is pre-installed on GitHub's `ubuntu-latest` runners
- If you self-host runners, ensure NDK 27.0.12077973 is installed and `ANDROID_NDK_HOME` is set

## Architecture

For detailed architecture documentation including data flow diagrams, component interactions, and design decision records, see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

```
hotspot-android/
├── app/                              Host app (shares mobile data)
│   └── src/main/java/.../hotspot/
│       ├── proxy/
│       │   ├── Socks5Server.kt       SOCKS5 proxy (RFC 1928, server-side DNS)
│       │   ├── DnsRelay.kt           UDP DNS forwarder with caching
│       │   ├── HttpCache.kt          HTTP response cache (port 80 only)
│       │   └── ProxyServer.kt        Proxy abstraction
│       ├── service/
│       │   ├── HotspotService.kt     Foreground service orchestrating components
│       │   ├── WifiDirectManager.kt  Wi-Fi Direct P2P group management
│       │   └── BluetoothServer.kt    RFCOMM server for Bluetooth transport
│       ├── model/
│       │   ├── HotspotState.kt       UI state for host screen
│       │   └── ConnectedDevice.kt    Connected peer metadata
│       ├── viewmodel/
│       │   └── HotspotViewModel.kt   Host screen ViewModel
│       └── ui/
│           ├── HotspotScreen.kt      Single-screen Compose UI
│           └── components/           Reusable UI components
│
├── client/                           Client app (connecting device)
│   ├── src/main/java/.../client/
│   │   ├── service/
│   │   │   ├── TunnelService.kt      VpnService -- creates TUN, manages tunnel
│   │   │   └── BluetoothTunnel.kt    Bluetooth RFCOMM client
│   │   ├── tunnel/
│   │   │   └── SocksTunnel.kt        YAML config + native library lifecycle
│   │   ├── model/
│   │   │   └── VpnState.kt           UI state for client screen
│   │   ├── viewmodel/
│   │   │   └── ClientViewModel.kt    Client screen ViewModel
│   │   └── ui/
│   │       └── ClientScreen.kt       Connect/disconnect UI
│   ├── src/main/java/hev/htproxy/
│   │   └── TProxyService.kt          JNI bridge to native library
│   └── src/main/jni/
│       └── hev-socks5-tunnel/        Native tun2socks (C, git submodule)
│           ├── src/                   SOCKS5 tunnel + JNI bindings
│           └── third-part/           lwIP, libyaml, hev-task-system
│
├── docs/
│   ├── ARCHITECTURE.md               Architecture decision records
│   └── SECURITY.md                   Threat model and security analysis
│
├── .github/workflows/
│   ├── android.yml                   CI: build + test + lint
│   ├── main.yml                      CD: auto-version on push to main
│   ├── release.yml                   Release: APKs + GitHub Release
│   └── dependency-scan.yml           Weekly dependency graph submission
│
├── ci/
│   └── rev-version.sh                Auto-increment version on main push
│
└── scripts/
    └── bump-version.sh               Manual version bump (X.Y format)
```

### Key design decisions

- **SOCKS5 (RFC 1928)** with server-side DNS resolution -- works with all TCP apps, not just HTTP
- **Native tun2socks** via [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) -- full userspace TCP/IP stack (lwIP) handles TCP and UDP relay. ~300KB per ABI
- **`addDisallowedApplication`** prevents VPN routing loops without root -- the client app's own SOCKS5 connections bypass the TUN and go directly to the host via Wi-Fi Direct. Same pattern as WireGuard for Android
- **Thread-per-connection** SOCKS5 server with bounded pool (4-32 threads) -- simple and sufficient for phone-to-phone use
- **Cellular-bound sockets** -- all outbound connections route through mobile data, never the P2P interface
- **No Android framework dependencies** in proxy/DNS code -- fully unit-testable on JVM
- **mapdns** virtual DNS -- client uses `198.18.0.2` as DNS, which hev-socks5-tunnel maps to real DNS queries through the SOCKS5 tunnel

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for full rationale and trade-off analysis.

## Documentation

| Document | Description |
|----------|-------------|
| [CONTRIBUTING.md](CONTRIBUTING.md) | How to contribute: build setup, code style, PR process |
| [docs/BUILD.md](docs/BUILD.md) | Detailed build instructions, NDK setup, signing, testing |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Architecture decision records, data flow, component details |
| [docs/TRANSPORT_GUIDE.md](docs/TRANSPORT_GUIDE.md) | Wi-Fi Direct vs Bluetooth vs USB comparison |
| [docs/SECURITY.md](docs/SECURITY.md) | Threat model, attack surface, security recommendations |
| [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | FAQ, OEM quirks, common issues and fixes |
| [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) | Contributor Covenant Code of Conduct |

## Known limitations

- **TCP-only SOCKS5** -- the SOCKS5 server handles TCP CONNECT only; UDP ASSOCIATE is not implemented. UDP traffic (VoIP, some games) will not work through the proxy path. DNS is handled separately via the DNS relay and mapdns.
- **No QUIC/HTTP3** -- QUIC uses UDP, so it falls back to TCP (HTTP/2 or HTTP/1.1). Most apps handle this transparently.
- **HTTPS not cacheable** -- the HTTP cache only operates on port 80 plaintext traffic. HTTPS connections (the majority of modern traffic) pass through uncached.
- **Single host device** -- only one phone can act as the host at a time. Multiple clients can connect to the same host.
- **Wi-Fi Direct naming** -- on Android < 10 (API 29), the network name and passphrase are randomly generated by the system and cannot be customized.

## Permissions

### Host

| Permission | Reason |
|------------|--------|
| `ACCESS_FINE_LOCATION` | Required by Android for Wi-Fi Direct (API 26-32) |
| `NEARBY_WIFI_DEVICES` | Wi-Fi Direct on API 33+ |
| `INTERNET` | Forward traffic through cellular |
| `FOREGROUND_SERVICE` | Keep hotspot active in background |

### Client

| Permission | Reason |
|------------|--------|
| `INTERNET` | Connect to SOCKS5 server |
| `FOREGROUND_SERVICE` | Keep VPN active |
| `BIND_VPN_SERVICE` | Android VPN consent (prompted once) |

Location is never tracked, stored, or transmitted.

## Security

For a detailed threat model, attack surface analysis, and security recommendations, see [docs/SECURITY.md](docs/SECURITY.md).

## CI/CD

Every push to `main` builds both APKs, runs tests, auto-increments the minor version (X.Y format), and creates a git tag. Pushing a `v*` tag triggers a release build with both APKs attached to a GitHub Release.

Dependencies are scanned weekly via GitHub's dependency submission API.

## Troubleshooting

See [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) for solutions to common issues including:

- Hotspot won't start (Wi-Fi, location, permission fixes)
- No internet on client device
- Bluetooth connection problems
- OEM-specific quirks (Samsung, Xiaomi, Huawei, OnePlus)
- Battery optimization killing background services

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for:

- Build setup and prerequisites
- Code style guidelines
- PR process and review expectations

## License

[Apache License 2.0](LICENSE)
