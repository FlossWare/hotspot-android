# FlossWare Hotspot

Free Android app that shares your phone's mobile data via Wi-Fi — bypasses carrier hotspot restrictions without root. All apps work transparently.

[![Android CI](https://github.com/FlossWare/hotspot-android/actions/workflows/android.yml/badge.svg)](https://github.com/FlossWare/hotspot-android/actions/workflows/android.yml)

## The Problem

Carriers block the built-in Android hotspot unless you pay for a tethering add-on. You already pay for the data — this app lets you use it.

## How It Works

Two apps work together — a **Host** on the phone sharing data, and a **Client** on the connecting device:

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

**Wi-Fi Direct** creates a peer-to-peer Wi-Fi network — not carrier tethering. The phone becomes a Group Owner that other devices connect to like a regular Wi-Fi access point.

**SOCKS5 Server** runs on the host phone and forwards connections through mobile data. The carrier sees normal app traffic from the phone's own process — TTL-based tethering detection is inherently bypassed.

**VPN Client** runs on the connecting device, captures ALL traffic via Android's VpnService, and tunnels it through SOCKS5 using [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) — a native C library with a full userspace TCP/IP stack (lwIP). Every app works: browsers, social media, streaming, games, everything. TCP and UDP.

**DNS Relay** forwards hostname lookups through mobile data so connected devices can resolve addresses.

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

Apps that support SOCKS5 proxies can connect directly — configure `192.168.49.1:1080` as the SOCKS5 server (e.g., Firefox: Settings → Network → SOCKS5).

## Building

```bash
git clone --recursive https://github.com/FlossWare/hotspot-android.git
cd hotspot-android
./gradlew assembleDebug
```

The `--recursive` flag is required — the client module includes [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) as a git submodule with nested dependencies (lwIP, libyaml, hev-task-system).

**Prerequisites:** Android SDK with NDK 27 installed. The NDK compiles the native tun2socks library for all four ABIs (arm64-v8a, armeabi-v7a, x86_64, x86).

APK outputs:
- Host: `app/build/outputs/apk/debug/app-debug.apk`
- Client: `client/build/outputs/apk/debug/client-debug.apk`

Run tests: `./gradlew test`

## Architecture

```
hotspot-android/
├── app/                              Host app (shares mobile data)
│   └── src/main/java/.../hotspot/
│       ├── proxy/
│       │   ├── Socks5Server.kt       SOCKS5 proxy (RFC 1928, server-side DNS)
│       │   └── DnsRelay.kt           UDP DNS forwarder
│       ├── service/
│       │   ├── HotspotService.kt     Foreground service orchestrating components
│       │   └── WifiDirectManager.kt  Wi-Fi Direct P2P group management
│       ├── model/
│       │   ├── HotspotState.kt
│       │   └── ConnectedDevice.kt
│       ├── viewmodel/
│       │   └── HotspotViewModel.kt
│       └── ui/
│           ├── HotspotScreen.kt      Single-screen Compose UI
│           └── components/
│
├── client/                           Client app (connecting device)
│   ├── src/main/java/.../client/
│   │   ├── service/
│   │   │   └── TunnelService.kt      VpnService — creates TUN, manages tunnel
│   │   ├── tunnel/
│   │   │   └── SocksTunnel.kt        YAML config + native library lifecycle
│   │   ├── model/
│   │   │   └── VpnState.kt
│   │   ├── viewmodel/
│   │   │   └── ClientViewModel.kt
│   │   └── ui/
│   │       └── ClientScreen.kt       Connect/disconnect UI
│   ├── src/main/java/hev/htproxy/
│   │   └── TProxyService.kt          JNI bridge to native library
│   └── src/main/jni/
│       └── hev-socks5-tunnel/        Native tun2socks (C, git submodule)
│           ├── src/                   SOCKS5 tunnel + JNI bindings
│           └── third-part/           lwIP, libyaml, hev-task-system
│
├── .github/workflows/
│   ├── android.yml                   CI: build + test + lint
│   ├── main.yml                      CD: auto-version on push to main
│   └── release.yml                   Release: APKs + GitHub Release
│
└── scripts/
    └── bump-version.sh               Manual version bump (X.Y format)
```

### Design Decisions

- **SOCKS5 (RFC 1928)** with server-side DNS resolution — works with all TCP apps, not just HTTP
- **Native tun2socks** via [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) — full userspace TCP/IP stack (lwIP) handles TCP and UDP relay. ~300KB per ABI.
- **`addDisallowedApplication`** prevents VPN routing loops without root — the client app's own SOCKS5 connections bypass the TUN and go directly to the host via Wi-Fi Direct. Same pattern as WireGuard for Android.
- **Thread-per-connection** SOCKS5 server with bounded pool (4-32 threads) — simple and sufficient for phone-to-phone use
- **Cellular-bound sockets** — all outbound connections route through mobile data, never the P2P interface
- **No Android framework dependencies** in proxy/DNS code — fully unit-testable on JVM

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

## CI/CD

Every push to `main` builds both APKs, runs tests, auto-increments the minor version (X.Y format), and creates a git tag. Pushing a `v*` tag triggers a release build with both APKs attached to a GitHub Release.

## License

[Apache License 2.0](LICENSE)
