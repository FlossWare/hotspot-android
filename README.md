# FlossWare Hotspot

Free, open-source Android app that shares your phone's mobile data via Wi-Fi — no carrier hotspot plan required, no root needed.

## Why?

US carriers like Verizon, AT&T, and T-Mobile block the built-in hotspot unless you pay extra. This app bypasses that restriction using Wi-Fi Direct + an HTTP proxy, so the carrier sees normal app traffic instead of tethering.

## How It Works

1. **Wi-Fi Direct Group** — Your phone creates a peer-to-peer Wi-Fi network (not carrier tethering). Other devices connect to it like a regular Wi-Fi network.

2. **HTTP Proxy** — An HTTP/HTTPS proxy runs on the phone. Connected devices route their traffic through it. Since the proxy makes requests from the phone itself, the carrier sees regular app traffic — TTL-based tethering detection is inherently bypassed.

3. **DNS Relay** — A lightweight DNS forwarder ensures connected devices can resolve hostnames.

## Requirements

- Android 8.0 (API 26) or higher
- Mobile data connection
- No root required

## Setup

### On the host phone (sharing data):
1. Install and open FlossWare Hotspot
2. Grant the requested permissions (location or nearby devices)
3. Tap **Start Hotspot**
4. Note the network name, password, and proxy address

### On connecting devices:
1. Connect to the displayed Wi-Fi network (or scan the QR code)
2. In your Wi-Fi settings, set the HTTP proxy to the displayed address (e.g., `192.168.49.1:8080`)
3. Optionally set DNS to `192.168.49.1:5353`
4. Browse the web normally

### Setting the proxy on different devices:

**Android:** Settings → Wi-Fi → long-press the network → Modify → Advanced → Proxy → Manual → enter host and port

**iOS:** Settings → Wi-Fi → tap the (i) icon → Configure Proxy → Manual → enter host and port

**Windows:** Settings → Network → Proxy → Manual → enter host and port

**macOS:** System Settings → Network → Wi-Fi → Details → Proxies → Web Proxy (HTTP) → enter host and port

## Building

```bash
git clone https://github.com/FlossWare/hotspot-android.git
cd hotspot-android
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Technical Details

- **No root** — Uses standard Android APIs (Wi-Fi Direct, `Network.socketFactory`)
- **No carrier detection** — Proxy traffic has the phone's native TTL; no DUN APN used
- **Minimal dependencies** — Only ZXing (QR codes) beyond standard AndroidX/Compose
- **Foreground service** — Keeps the hotspot alive with a persistent notification

## Permissions

| Permission | Why |
|---|---|
| `ACCESS_FINE_LOCATION` | Required by Android for Wi-Fi Direct (API 26-32) |
| `NEARBY_WIFI_DEVICES` | Required for Wi-Fi Direct (API 33+) |
| `INTERNET` | Proxy forwards traffic to the internet |
| `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` | Create and manage Wi-Fi Direct group |
| `FOREGROUND_SERVICE` | Keep hotspot running in background |
| `POST_NOTIFICATIONS` | Show persistent notification (API 33+) |

Your location is never tracked, stored, or transmitted.

## License

Apache License 2.0 — see [LICENSE](LICENSE)
