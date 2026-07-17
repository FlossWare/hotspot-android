# FlossWare Hotspot

Free Android app that shares your phone's mobile data via Wi-Fi — bypasses carrier hotspot restrictions without root. All apps work transparently.

[![Android CI](https://github.com/FlossWare/hotspot-android/actions/workflows/android.yml/badge.svg)](https://github.com/FlossWare/hotspot-android/actions/workflows/android.yml)

## The Problem

Carriers like Verizon, AT&T, and T-Mobile block the built-in Android hotspot unless you pay for a tethering add-on. You already pay for the data — this app lets you use it.

## How It Works

Two apps work together — a **Host** on the phone sharing data, and a **Client** on the connecting device:

```
HOST PHONE (app/)                        CLIENT DEVICE (client/)
┌──────────────────────┐                 ┌──────────────────────┐
│  Wi-Fi Direct Group  │   P2P Wi-Fi     │                      │
│  Owner               │◄───────────────│  Connect to SSID     │
│                      │                 │                      │
│  Socks5Server :1080  │◄── SOCKS5 ─────│  TunnelService       │
│                      │    (TCP)        │  (VpnService)        │
│                      │                 │                      │
│  DnsRelay :5353      │◄── DNS ────────│  tun2socks           │
│                      │    (UDP)        │  (TUN fd → SOCKS5)   │
│                      │                 │                      │
│  Cellular Data ──────┼──► Internet     │  All app traffic     │
│                      │                 │  captured by VPN     │
└──────────────────────┘                 └──────────────────────┘
```

**Wi-Fi Direct** creates a peer-to-peer Wi-Fi network — not carrier tethering. The phone becomes a Group Owner that other devices connect to like a regular Wi-Fi access point.

**SOCKS5 Server** runs on the host phone and forwards all TCP connections through mobile data. Since the server process runs on the phone itself, the carrier sees normal app traffic. TTL-based tethering detection is inherently bypassed.

**VPN Client** runs on the connecting device, captures ALL traffic via Android's VpnService, and tunnels it through the SOCKS5 server. Every app works — Facebook, Instagram, TikTok, YouTube, games, everything.

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
2. Connect to the displayed Wi-Fi network (or scan QR code)
3. Open FlossWare Hotspot Client
4. Tap **Connect**

All traffic is now routed through the host's mobile data. No per-app configuration needed.

### Manual mode (no client app)

If you can't install the client app, you can manually configure SOCKS5 in individual apps that support it (e.g., Firefox: Settings → Network → SOCKS5 → `192.168.49.1:1080`).

## Building

```bash
git clone https://github.com/FlossWare/hotspot-android.git
cd hotspot-android
./gradlew assembleDebug
```

APK outputs:
- Host: `app/build/outputs/apk/debug/app-debug.apk`
- Client: `client/build/outputs/apk/debug/client-debug.apk`

Release build: `./gradlew assembleRelease`

Run tests: `./gradlew test`

## CI/CD

| Workflow | Trigger | Action |
|----------|---------|--------|
| `android.yml` | Push/PR | Build, test, lint, upload APK artifacts |
| `main.yml` | Push to main | Build + auto-increment version + tag |
| `release.yml` | Tag `v*` | Build release APKs, create GitHub Release with both apps |
| `dependency-scan.yml` | Weekly | Gradle dependency graph submission |

Versioning follows X.Y format. Every push to `main` auto-increments the minor version and creates a tag.

## Architecture

```
hotspot-android/
├── app/                            Host app (shares mobile data)
│   └── src/main/java/org/flossware/hotspot/
│       ├── proxy/
│       │   ├── Socks5Server.kt     SOCKS5 proxy (RFC 1928, server-side DNS)
│       │   └── DnsRelay.kt         UDP DNS forwarder
│       ├── service/
│       │   ├── HotspotService.kt   Foreground service orchestrating all components
│       │   └── WifiDirectManager.kt  Wi-Fi Direct P2P group management
│       ├── model/
│       │   ├── HotspotState.kt     UI state
│       │   └── ConnectedDevice.kt  Connected peer data
│       ├── viewmodel/
│       │   └── HotspotViewModel.kt
│       ├── ui/
│       │   ├── HotspotScreen.kt    Single-screen Compose UI
│       │   └── components/         Toggle, ConnectionInfo, QrCode, DeviceList, SetupInstructions
│       ├── MainActivity.kt
│       └── HotspotApp.kt
│
└── client/                         Client app (installed on connecting devices)
    └── src/main/java/org/flossware/hotspot/client/
        ├── service/
        │   └── TunnelService.kt    VpnService — creates TUN, routes through SOCKS5
        ├── tunnel/
        │   └── SocksTunnel.kt      Userspace TCP-over-SOCKS5 (tun2socks)
        ├── model/
        │   └── VpnState.kt         VPN connection state
        ├── viewmodel/
        │   └── ClientViewModel.kt
        ├── ui/
        │   └── ClientScreen.kt     Connect/disconnect UI
        ├── MainActivity.kt
        └── ClientApp.kt
```

**Key design decisions:**
- SOCKS5 protocol (RFC 1928) with server-side DNS resolution — works with all TCP apps, not just HTTP
- Thread-per-connection proxy (bounded pool, 4-32 threads) — simple and sufficient for phone-to-phone use
- All outbound sockets bound to cellular `Network` to ensure traffic routes through mobile data
- VPN client captures all device traffic via TUN interface — zero per-app configuration
- No Android framework dependencies in proxy/DNS code — fully unit-testable on JVM

## Permissions

### Host app

| Permission | Reason |
|------------|--------|
| `ACCESS_FINE_LOCATION` | Required by Android for Wi-Fi Direct (API 26-32) |
| `NEARBY_WIFI_DEVICES` | Wi-Fi Direct on API 33+ |
| `INTERNET` | Forward proxy traffic through cellular |
| `FOREGROUND_SERVICE` | Keep hotspot active in background |

### Client app

| Permission | Reason |
|------------|--------|
| `INTERNET` | Connect to SOCKS5 server |
| `FOREGROUND_SERVICE` | Keep VPN active |
| `BIND_VPN_SERVICE` | Android VPN consent (prompted once) |

Location is never tracked, stored, or transmitted.

## License

[Apache License 2.0](LICENSE)
