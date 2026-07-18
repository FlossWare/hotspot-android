# Troubleshooting

Common issues, their causes, and how to fix them. If your issue is not listed here, check the [GitHub Issues](https://github.com/FlossWare/hotspot-android/issues) or open a new one.

## Hotspot Won't Start

### "Wi-Fi Direct failed" or "Failed to create hotspot network"

**Symptoms:** The host app shows an error when tapping Start Hotspot.

**Causes and fixes:**

| Check | Fix |
|-------|-----|
| Wi-Fi is off | Turn on Wi-Fi. Some devices require Wi-Fi to be enabled for Wi-Fi Direct, even though the hotspot creates its own separate network. |
| Location Services disabled | Enable Location Services in Android settings. Required on Android 12 and below. |
| Permissions not granted | Open the app, go to Android Settings > Apps > FlossWare Hotspot > Permissions, and grant Location (API 26-32) or Nearby Devices (API 33+). |
| Another Wi-Fi Direct group is active | Disconnect from any existing Wi-Fi Direct groups. Only one P2P group can be active at a time. |
| Wi-Fi Direct hardware issue | Toggle Wi-Fi off and on, then try again. If that fails, reboot the device. |

**What the app does automatically:** The app retries group creation up to 2 times with increasing delays (1s, 2s) before showing an error.

### "Wi-Fi Direct is not supported on this device"

The device hardware or firmware does not support Wi-Fi Direct. This cannot be worked around. Consider using the Bluetooth transport instead.

### "Mobile data is not available"

The host phone does not have an active cellular data connection. The hotspot shares mobile data, so it must be active:

1. Ensure mobile data is turned on in Android settings
2. Verify you have cellular signal
3. Check that you have not exceeded your data plan

## Client Cannot Connect to Wi-Fi Network

### Network not visible

- The host hotspot must be running (green "Hotspot Active" indicator)
- On Android 8-9, the network name changes each time the hotspot is restarted -- get the current name from the host app screen
- Move the client device closer to the host (Wi-Fi Direct range is approximately 200 feet / 60 meters, but walls and obstacles reduce it)

### Wrong password

- On Android 10+, the password is `FlossWare2024` (displayed on the host screen)
- On Android 8-9, the password is randomly generated -- copy it from the host app screen
- If using the QR code, scan it from the client device's camera app or Wi-Fi settings

## No Internet on Client

### Client connects to Wi-Fi but has no internet

**Step-by-step diagnosis:**

1. **Check mobile data on host:** The host phone must have an active cellular data connection. Open a browser on the host phone itself and verify it can load a webpage.

2. **Check the client app is connected:** Open FlossWare Hotspot Client and verify it shows "Connected." The VPN must be active (look for the key icon in the Android status bar).

3. **Check DNS:** If websites do not load but IP addresses work, DNS relay may be the issue. Try loading `http://1.1.1.1` in the client browser. If that works but `http://google.com` does not, DNS resolution is failing.

4. **Check SOCKS5 connection:** The client connects to `192.168.49.1:1080` (Wi-Fi Direct) or `127.0.0.1:<port>` (Bluetooth). If the VPN connects but no traffic flows, the SOCKS5 server on the host may have stopped.

5. **Restart both apps:** Stop the hotspot on the host, disconnect the client, and start again.

### Manual SOCKS5 mode does not work

If you are using manual SOCKS5 configuration (e.g., in Firefox):

- Server address: `192.168.49.1`
- Port: `1080`
- Protocol: SOCKS5 (not SOCKS4, not HTTP)
- Enable "Proxy DNS when using SOCKS v5" in the browser to route DNS through the proxy

### VPN permission denied

Android requires explicit user consent to start a VPN. If the VPN consent dialog was dismissed or denied:

1. Go to Android Settings > Network & internet > VPN
2. Remove FlossWare Hotspot Client if listed
3. Open the client app and tap Connect again
4. Accept the VPN consent dialog

## Bluetooth Issues

### Bluetooth not connecting

| Check | Fix |
|-------|-----|
| Devices not paired | Open Android Settings > Bluetooth on both devices and pair them before using Bluetooth transport. |
| Bluetooth disabled on host | The host must have Bluetooth enabled in Android settings AND the Bluetooth toggle enabled in the FlossWare Hotspot app (under "Bluetooth Transport"). |
| Bluetooth toggle not opt-in | Bluetooth is disabled by default in the host app. Enable the toggle in the Bluetooth Transport section. |
| Wrong transport selected on client | In the client app, select "Bluetooth" in the transport selector at the top of the screen (not "Wi-Fi Direct"). |
| Bluetooth permissions denied | Grant Bluetooth Connect and Bluetooth Scan permissions in Android settings. |

### Bluetooth is very slow

This is expected. Bluetooth RFCOMM provides approximately 2 Mbps, compared to ~250 Mbps for Wi-Fi Direct. Bluetooth is suitable for light browsing and messaging, not streaming or large downloads. The extra relay hops (VPN -> local TCP -> RFCOMM -> host TCP -> SOCKS5) also add latency.

### "Bluetooth not supported"

The device does not have Bluetooth hardware. Bluetooth is declared as an optional feature (`android:required="false"`) in the app manifest.

## Connection Drops

### Wi-Fi Direct disconnects frequently

- **Range:** Move devices closer together. Wi-Fi Direct range drops significantly through walls and with interference from other Wi-Fi networks.
- **Power saving:** Some devices aggressively kill background services. See the "Battery Optimization" section below.
- **Channel interference:** If you are in a dense Wi-Fi environment, interference can cause drops. Moving to a different location may help.

### VPN disconnects after a while

- **Battery optimization:** Android may kill the VPN service. See "Battery Optimization" below.
- **Host hotspot stopped:** If the host phone went to sleep or the hotspot service was killed, the client loses connectivity. Check the host app.
- **Network switch:** If the host phone switches from cellular to Wi-Fi, the SOCKS5 server's cellular binding is lost. Ensure the host phone stays on mobile data.

## OEM-Specific Quirks

Different manufacturers modify Android in ways that affect Wi-Fi Direct and background services. Known issues:

### Samsung

- **Wi-Fi must be ON:** Samsung devices require Wi-Fi to be enabled for Wi-Fi Direct to work, even though the hotspot creates its own network. If Wi-Fi is off, group creation fails.
- **Smart Network Switch:** Some Samsung models have "Smart Network Switch" or "Switch to mobile data" that can interfere. Disable it in Wi-Fi settings > Advanced.

### Xiaomi / Redmi / POCO

- **Wi-Fi must be ON:** Same as Samsung -- Wi-Fi must be enabled.
- **MIUI battery optimization:** MIUI aggressively kills background services. Go to Settings > Apps > FlossWare Hotspot > Battery saver > No restrictions.
- **Autostart:** Enable autostart for FlossWare Hotspot in MIUI Security app > Manage apps > Permissions > Autostart.

### Huawei / Honor

- **App launch management:** Go to Settings > Battery > App launch, and set FlossWare Hotspot to "Manage manually" with all toggles enabled.
- **Power-intensive app prompt:** Dismiss or whitelist when prompted.

### OnePlus / Oppo / Realme

- **Battery optimization:** Go to Settings > Battery > Battery optimization > FlossWare Hotspot > Don't optimize.
- **ColorOS auto-optimize:** Disable "Auto-optimize" in Settings > Battery.

### Google Pixel

- **Generally reliable.** Wi-Fi Direct and background services work well with minimal OEM interference.
- **Adaptive Battery:** If connections drop after extended idle, go to Settings > Battery > Adaptive preferences > Adaptive Battery, and ensure FlossWare Hotspot is not restricted.

## Battery Optimization

Android's battery optimization can kill the hotspot or VPN service when the device is idle. The app acquires a `PARTIAL_WAKE_LOCK` to stay alive, but some OEM power management can override this.

**For all devices:**

1. Go to **Settings > Apps > FlossWare Hotspot** (or Hotspot Client)
2. Tap **Battery**
3. Select **Unrestricted** (or **Don't optimize**)

The host app automatically releases the wake lock when no data has been transferred for 30 seconds (15 polls at 2-second intervals) and reacquires it when traffic resumes.

## Collecting Logs

If you need to report a bug or debug an issue, collect logs with `adb`:

```bash
# Host-side logs
adb logcat -s HotspotService Socks5Server DnsRelay WifiDirectManager BluetoothServer ProxyManager NetworkManager

# Client-side logs
adb logcat -s TunnelService SocksTunnel BluetoothTunnel

# Enable debug mode in the app for more verbose logging
# (BluetoothServer and BluetoothTunnel have a debugMode flag)
```

### Key Log Tags

| Tag | Module | What It Shows |
|-----|--------|--------------|
| `HotspotService` | Host | Service lifecycle, state transitions |
| `Socks5Server` | Host | SOCKS5 connections, relay errors |
| `DnsRelay` | Host | DNS queries, cache hits/misses |
| `WifiDirectManager` | Host | Group creation, peer changes |
| `BluetoothServer` | Host | Bluetooth RFCOMM connections |
| `TunnelService` | Client | VPN lifecycle, tunnel state |
| `SocksTunnel` | Client | Native tunnel start/stop |
| `BluetoothTunnel` | Client | Bluetooth relay connections |

## Known Limitations

- **TCP only:** The SOCKS5 server handles TCP CONNECT only. UDP-based apps (some VoIP, certain games) will not work through the proxy. DNS is handled separately.
- **No QUIC/HTTP3:** QUIC uses UDP, so browsers fall back to HTTP/2 or HTTP/1.1 over TCP. This is transparent to the user.
- **HTTPS not cacheable:** The HTTP cache only works on port 80 plaintext traffic. HTTPS (the majority of modern traffic) passes through uncached.
- **One VPN at a time:** Android allows only one active VPN. The client app cannot coexist with other VPN apps.
- **No concurrent VPN usage:** If another VPN app is active, the client will either fail to connect or replace the existing VPN.
