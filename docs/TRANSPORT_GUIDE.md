# Transport Guide

FlossWare Hotspot supports multiple transport layers for connecting the client device to the host. This guide explains each transport, when to use it, and the trade-offs involved.

## Overview

The host phone always runs the same SOCKS5 server and DNS relay regardless of which transport the client uses. The transport only affects how the client reaches the host -- once connected, the tunneling behavior is identical.

```
Client Device                      Host Phone
┌──────────────┐                   ┌──────────────┐
│  VpnService  │                   │ Socks5Server │
│  + TUN       │                   │   :1080      │
│              │   [Transport]     │              │
│  SocksTunnel ├───────────────────┤  DnsRelay    │
│              │  Wi-Fi Direct     │   :5353      │
│              │  Bluetooth        │              │
│              │  (USB - planned)  │  Cellular    │
│              │                   │  Data ──► Internet
└──────────────┘                   └──────────────┘
```

## Transport Comparison

| Property | Wi-Fi Direct | Bluetooth | USB (planned) |
|----------|-------------|-----------|---------------|
| **Speed** | ~250 Mbps | ~2 Mbps | ~480 Mbps (USB 2.0) |
| **Range** | ~200 ft / 60 m | ~30 ft / 10 m | Cable length |
| **Max connections** | Limited by device | ~7 concurrent | 1 (point-to-point) |
| **Pairing required** | No (passphrase) | Yes (system-level) | No |
| **Setup complexity** | Low | Medium | Low |
| **Status** | Default, stable | Experimental, opt-in | Not yet implemented |
| **Best for** | General use | When Wi-Fi Direct unavailable | Reliable wired connection |
| **Permissions** | Location (API 26-32), Nearby Devices (API 33+) | Bluetooth Connect, Bluetooth Scan | TBD |
| **Works in emulator** | No | No | TBD |

## Wi-Fi Direct

### How It Works

Wi-Fi Direct creates a peer-to-peer Wi-Fi network independent of the phone's regular Wi-Fi connection. The host phone becomes the Group Owner, and client devices connect to it like a regular Wi-Fi access point.

```
Host creates P2P group
    │
    ├── Network name: DIRECT-FW-FlossHotspot (Android 10+)
    │                 DIRECT-xx-random     (Android 8-9)
    │
    ├── Passphrase:   User-configurable     (Android 10+)
    │                 System-generated      (Android 8-9)
    │
    ├── Group Owner:  192.168.49.1 (always)
    │
    └── SOCKS5:       192.168.49.1:1080
        DNS Relay:    192.168.49.1:5353
```

### When to Use

Wi-Fi Direct is the default and recommended transport. Use it whenever both devices support it.

### Advantages

- Highest throughput (~250 Mbps, limited by Wi-Fi Direct spec)
- No Bluetooth pairing step required
- Works at greater range than Bluetooth
- Deterministic network name and configurable passphrase on Android 10+
- QR code sharing for easy setup

### Limitations

- Requires Location permission on Android 8-12 (this is an Android platform requirement for Wi-Fi Direct, not a FlossWare design choice)
- Requires Nearby Devices permission on Android 13+
- Network name and passphrase are randomly generated on Android 8-9 (changes each time the hotspot is restarted)
- Some OEMs have buggy Wi-Fi Direct implementations (see [Troubleshooting](TROUBLESHOOTING.md))
- Not available in Android emulators

### Permissions (Host)

| Permission | API Level | Reason |
|------------|-----------|--------|
| `ACCESS_FINE_LOCATION` | 26-32 | Android requires location for Wi-Fi Direct |
| `NEARBY_WIFI_DEVICES` | 33+ | Replaces location for Wi-Fi Direct |
| `ACCESS_WIFI_STATE` | All | Read Wi-Fi Direct state |
| `CHANGE_WIFI_STATE` | All | Create/remove P2P groups |

## Bluetooth

### How It Works

Bluetooth uses RFCOMM (serial port emulation) to create a bridge between the client and the host's local SOCKS5 server. The architecture is a relay chain:

```
Client device                         Host phone
┌──────────────┐                      ┌──────────────────┐
│ VPN + TUN    │                      │ BluetoothServer  │
│      │       │                      │       │          │
│ SocksTunnel  │                      │  Bridges to      │
│      │       │                      │  localhost:1080  │
│ BluetoothTunnel                     │  (SOCKS5 server) │
│      │       │   RFCOMM stream      │                  │
│  local TCP   │─────────────────────>│                  │
│  127.0.0.1   │                      └──────────────────┘
└──────────────┘
```

1. The host starts a Bluetooth RFCOMM server (service name: `FlossHotspotSOCKS`)
2. The client discovers and connects to the host via Bluetooth
3. The client opens a local TCP server on `127.0.0.1` (random port)
4. The VPN tunnel connects to this local server instead of the Wi-Fi Direct address
5. Each TCP connection from the VPN is relayed over a Bluetooth RFCOMM channel to the host
6. The host's BluetoothServer bridges each RFCOMM connection to `localhost:1080` (the SOCKS5 server)

### When to Use

Bluetooth is a fallback transport for situations where Wi-Fi Direct does not work:

- Device has buggy Wi-Fi Direct implementation
- Wi-Fi is disabled or unavailable
- Testing without Wi-Fi

### Enabling Bluetooth

Bluetooth is **opt-in** and disabled by default.

**On the host:**
1. Enable the Bluetooth toggle in the host app UI (under the "Bluetooth Transport" section)
2. Grant Bluetooth permissions when prompted

**On the client:**
1. Pair the client device with the host device through Android's Bluetooth settings
2. Select "Bluetooth" in the transport selector (segmented button at the top of the client app)
3. Select the paired host device

### Advantages

- Universally available on Android devices
- Works when Wi-Fi Direct is unavailable
- System-level Bluetooth pairing provides authentication

### Limitations

- **Bandwidth:** ~2 Mbps, suitable for browsing and messaging but not streaming
- **Latency:** Higher than Wi-Fi Direct due to the relay chain (VPN -> local TCP -> RFCOMM -> host TCP -> SOCKS5)
- **Connections:** Approximately 7 concurrent RFCOMM connections (Bluetooth specification limit)
- **Pairing required:** Devices must be paired through Android settings before connecting
- **Experimental:** Labeled as experimental in the UI

### Permissions (Host)

| Permission | API Level | Reason |
|------------|-----------|--------|
| `BLUETOOTH` | Up to 30 | Legacy Bluetooth access |
| `BLUETOOTH_ADMIN` | Up to 30 | Legacy Bluetooth management |
| `BLUETOOTH_CONNECT` | 31+ | Connect to paired devices |
| `BLUETOOTH_ADVERTISE` | 31+ | Advertise RFCOMM service |

### Permissions (Client)

| Permission | API Level | Reason |
|------------|-----------|--------|
| `BLUETOOTH` | Up to 30 | Legacy Bluetooth access |
| `BLUETOOTH_ADMIN` | Up to 30 | Legacy Bluetooth management |
| `BLUETOOTH_CONNECT` | 31+ | Connect to host device |
| `BLUETOOTH_SCAN` | 31+ | Discover host device |
| `ACCESS_FINE_LOCATION` | All | Required for Bluetooth scanning on some devices |

## USB (Planned)

USB transport is not yet implemented. When available, it will provide:

- Most reliable connection (no wireless interference)
- Highest bandwidth (USB 2.0: ~480 Mbps)
- No pairing or passphrase required
- Works in environments where wireless is restricted
- Point-to-point only (one client per host)

The implementation would likely use Android's USB accessory or host mode to establish a network link over the cable, then route to the same SOCKS5 server.

## Choosing a Transport

| Scenario | Recommended Transport |
|----------|-----------------------|
| General internet sharing | Wi-Fi Direct |
| Browsing and messaging when Wi-Fi Direct fails | Bluetooth |
| Streaming video or large downloads | Wi-Fi Direct |
| Multiple client devices | Wi-Fi Direct |
| Testing without Wi-Fi hardware | Bluetooth |
| Maximum reliability (future) | USB |

## Technical Details

### Service UUID

Both BluetoothServer (host) and BluetoothTunnel (client) use the same service UUID for RFCOMM discovery:

```
a1b2c3d4-e5f6-7890-abcd-ef1234567890
```

### Thread Pools

| Component | Core | Max | Queue | Overflow Policy |
|-----------|------|-----|-------|-----------------|
| BluetoothServer (host) | 2 | 16 | 16 | CallerRunsPolicy |
| BluetoothTunnel (client) | 2 | 16 | 16 | CallerRunsPolicy |
| Socks5Server | 4 | 32 | 64 | CallerRunsPolicy |
| DnsRelay | 4 | 8 | 32 | CallerRunsPolicy |

### Relay Buffer Size

All relay connections (Bluetooth and SOCKS5) use 8192-byte buffers for bidirectional data transfer.
