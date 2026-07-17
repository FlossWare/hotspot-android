# FlossWare Hotspot

Free Android app that shares your phone's mobile data via Wi-Fi — bypasses carrier hotspot restrictions without root.

[![Android CI](https://github.com/FlossWare/hotspot-android/actions/workflows/android.yml/badge.svg)](https://github.com/FlossWare/hotspot-android/actions/workflows/android.yml)

## The Problem

Carriers like Verizon, AT&T, and T-Mobile block the built-in Android hotspot unless you pay for a tethering add-on. You already pay for the data — this app lets you use it.

## How It Works

```
┌──────────────┐    Wi-Fi Direct    ┌──────────────┐
│   Phone B    │◄──────────────────►│   Phone A    │
│  (client)    │   P2P connection   │  (hotspot)   │
│              │                    │              │
│ proxy config ├───HTTP/HTTPS──────►│ ProxyServer  │──► Mobile Data ──► Internet
│ 192.168.49.1 │    :8080           │ :8080        │
│              │                    │              │
│ DNS config   ├───UDP─────────────►│ DnsRelay     │──► Upstream DNS
│ 192.168.49.1 │    :5353           │ :5353        │
└──────────────┘                    └──────────────┘
```

**Wi-Fi Direct** creates a peer-to-peer Wi-Fi network — not carrier tethering. The phone becomes a Group Owner that other devices connect to like a regular Wi-Fi access point.

**HTTP Proxy** runs on the phone and forwards requests through the mobile data connection. Since the proxy process runs on the phone itself, the carrier sees normal app traffic. TTL-based tethering detection is inherently bypassed because the proxy originates its own TCP connections.

**DNS Relay** forwards hostname lookups through mobile data so connected devices can resolve addresses.

## Requirements

- Android 8.0+ (API 26)
- Active mobile data connection
- No root required

## Quick Start

### Host phone (sharing data)

1. Install FlossWare Hotspot
2. Grant permissions (location for Wi-Fi Direct)
3. Tap **Start Hotspot**

### Client device (connecting)

1. Connect to the displayed Wi-Fi network (or scan QR code)
2. Set HTTP proxy to `192.168.49.1:8080` in Wi-Fi settings
3. Browse normally

### Proxy setup by platform

| Platform | Path |
|----------|------|
| Android | Settings → Wi-Fi → long-press network → Modify → Advanced → Proxy → Manual |
| iOS | Settings → Wi-Fi → (i) → Configure Proxy → Manual |
| Windows | Settings → Network → Proxy → Manual |
| macOS | System Settings → Network → Wi-Fi → Details → Proxies → Web Proxy (HTTP) |
| Linux | Settings → Network → Wi-Fi → gear icon → IPv4 → Manual proxy |

## Building

```bash
git clone https://github.com/FlossWare/hotspot-android.git
cd hotspot-android
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

Release build: `./gradlew assembleRelease`

Run tests: `./gradlew test`

## CI/CD

| Workflow | Trigger | Action |
|----------|---------|--------|
| `android.yml` | Push/PR | Build, test, lint, upload APK artifacts |
| `main.yml` | Push to main | Build + auto-increment version + tag |
| `release.yml` | Tag `v*` | Build release APK, create GitHub Release |
| `dependency-scan.yml` | Weekly | Gradle dependency graph submission |

Versioning follows X.Y format. Every push to `main` auto-increments the minor version and creates a tag.

## Architecture

```
org.flossware.hotspot/
├── proxy/
│   ├── ProxyServer.kt      HTTP/HTTPS proxy (CONNECT tunneling + forward proxy)
│   └── DnsRelay.kt         UDP DNS forwarder
├── service/
│   ├── HotspotService.kt   Foreground service orchestrating all components
│   └── WifiDirectManager.kt  Wi-Fi Direct P2P group management
├── model/
│   ├── HotspotState.kt     UI state
│   └── ConnectedDevice.kt  Connected peer data
├── viewmodel/
│   └── HotspotViewModel.kt
├── ui/
│   ├── HotspotScreen.kt    Single-screen Compose UI
│   └── components/          Toggle, ProxyInfo, QrCode, DeviceList, SetupInstructions
├── MainActivity.kt
└── HotspotApp.kt
```

**Key design decisions:**
- Thread-per-connection proxy (bounded pool, 4-32 threads) — simple and sufficient for phone-to-phone use
- All outbound sockets bound to cellular `Network` to ensure traffic routes through mobile data
- No Android framework dependencies in proxy/DNS code — fully unit-testable on JVM
- Single external dependency: ZXing for QR code generation

## Permissions

| Permission | Reason |
|------------|--------|
| `ACCESS_FINE_LOCATION` | Required by Android for Wi-Fi Direct (API 26-32) |
| `NEARBY_WIFI_DEVICES` | Wi-Fi Direct on API 33+ |
| `INTERNET` | Forward proxy traffic |
| `FOREGROUND_SERVICE` | Keep hotspot active in background |

Location is never tracked, stored, or transmitted.

## License

[Apache License 2.0](LICENSE)
