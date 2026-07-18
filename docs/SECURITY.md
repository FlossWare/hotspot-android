# Security Model

This document describes the threat model, attack surface, and security properties of FlossWare Hotspot.

## Overview

FlossWare Hotspot creates a local network between two Android devices and forwards traffic from the client through the host's mobile data connection. It is designed for personal use between trusted devices on a private peer-to-peer link.

**This is not an enterprise VPN.** There is no encryption of the proxy tunnel, no authentication on the SOCKS5 server, and no user identity management. The security model relies on the isolation properties of Wi-Fi Direct and the assumption that both devices are controlled by the same person.

## Threat model

### Assets

| Asset | Description |
|-------|-------------|
| Client traffic | All network traffic from the client device passes through the host |
| DNS queries | Client DNS lookups are forwarded through the host |
| Host mobile data | The host's cellular data connection is shared |
| Wi-Fi Direct credentials | Network name and passphrase for the P2P group |

### Threat actors

| Actor | Capability | Motivation |
|-------|-----------|------------|
| Nearby attacker | Within Wi-Fi/Bluetooth range; can scan for Wi-Fi Direct groups | Intercept traffic, use free internet |
| Carrier | Can inspect traffic from the host phone's IP | Detect tethering, throttle or block |
| Malicious app on client | Installed app on the client device | Exploit proxy for data exfiltration |
| Malicious app on host | Installed app on the host device | Intercept proxied traffic |

### What is protected

- **Traffic isolation:** Wi-Fi Direct creates a separate network from the phone's regular Wi-Fi. Devices on the user's home Wi-Fi cannot see the P2P group.
- **TTL-based detection bypass:** Outbound connections from the SOCKS5 server use `Network.socketFactory` bound to the cellular network. The carrier sees packets originating from the host phone's own process with the phone's native TTL -- not from a tethered device with a decremented TTL.
- **VPN loop prevention:** The client app excludes itself from VPN routing via `addDisallowedApplication(packageName)`, preventing infinite loops where SOCKS5 traffic would be re-captured by the TUN interface.

### What is NOT protected

- **No tunnel encryption:** Traffic between the client and host travels over Wi-Fi Direct in plaintext. Any device that joins the P2P group can sniff the traffic. This is mitigated by the fact that Wi-Fi Direct uses WPA2 encryption at the link layer, but the SOCKS5 proxy traffic itself is not additionally encrypted.
- **No SOCKS5 authentication:** The SOCKS5 server accepts connections from any client on the P2P network without authentication (`AUTH_NONE`, RFC 1928 method `0x00`). Any device that knows the Wi-Fi Direct passphrase and the server address (`192.168.49.1:1080`) can use the proxy.
- **No traffic inspection:** The host does not inspect, filter, or validate proxied traffic. A malicious client could use the connection for any purpose.
- **No integrity verification:** There is no mechanism to verify that responses from the internet have not been tampered with by the host. The client trusts the host completely.

## Attack surface analysis

### 1. Wi-Fi Direct group

**Risk: Unauthorized access to the P2P group**

| Property | Detail |
|----------|--------|
| Encryption | WPA2 (provided by Android Wi-Fi Direct) |
| Authentication | Pre-shared passphrase |
| Passphrase (API 29+) | User-configurable (random default generated on first launch) |
| Passphrase (API < 29) | Random, system-generated per group creation |
| Discoverability | Wi-Fi Direct groups are discoverable by nearby devices |

**Analysis:** On Android 10+ (API 29), the passphrase is user-configurable via the host app UI. A cryptographically random passphrase is generated on first launch and stored in SharedPreferences. Users can change it before starting the hotspot. The network name (`DIRECT-FW-FlossHotspot`) is deterministic and identifiable.

**Mitigation:** The passphrase is shared via the host UI (and optionally via QR code). Users should only share it with their own devices. For higher security, users on Android < 10 get a random passphrase per session.

### 2. SOCKS5 server

**Risk: Unauthorized proxy usage**

| Property | Detail |
|----------|--------|
| Bind address | P2P interface (`192.168.49.1:1080`) + loopback (`127.0.0.1:1080`) |
| Authentication | None (`AUTH_NONE`) |
| Commands | CONNECT only (no BIND, no UDP ASSOCIATE) |
| Address types | IPv4, IPv6, domain name |
| Timeout | 60 seconds read/write |

**Analysis:** The server binds to the P2P interface address, limiting access to devices on the Wi-Fi Direct group. However, there is no application-level authentication. Any device on the P2P network can use the proxy.

The loopback instance (`127.0.0.1:1080`) exists for the Bluetooth transport bridge. It is not accessible from the network.

**Input validation:**
- SOCKS5 version byte is checked (`0x05`)
- Authentication method negotiation follows RFC 1928
- Only CONNECT command is supported; unsupported commands return `REPLY_CMD_NOT_SUPPORTED`
- Domain names are length-prefixed (1-255 bytes) with explicit length validation
- Port numbers are read as two bytes (big-endian), range 0-65535
- Read operations use explicit byte counting with EOF detection

### 3. DNS relay

**Risk: DNS spoofing or cache poisoning**

| Property | Detail |
|----------|--------|
| Bind address | P2P interface (`192.168.49.1:5353`) |
| Upstream | Carrier DNS (from `LinkProperties`) or `8.8.8.8` fallback |
| Cache | Up to 1000 entries, TTL-based expiration (10s-3600s) |
| Protocol | UDP (standard DNS, no DoH/DoT) |

**Analysis:** The DNS relay forwards queries over plaintext UDP to the carrier's DNS servers. DNS responses are cached but not validated with DNSSEC. An attacker on the cellular network could potentially poison DNS responses, but this is the same risk as any device using the carrier's DNS directly.

The cache keys on the question section (excluding the transaction ID), which prevents simple cache poisoning via transaction ID guessing. However, source port randomization for upstream queries depends on the system's `DatagramSocket` implementation.

**DNS amplification:** The relay binds only to the P2P interface address (`192.168.49.1`), not `0.0.0.0`, so it is not reachable from the internet or the phone's regular Wi-Fi network. This limits the amplification attack surface to devices already on the Wi-Fi Direct group. The relay does not perform recursion -- it forwards to upstream DNS servers -- so the amplification factor is limited to the response-to-query size ratio of standard DNS.

### 4. Native JNI library (hev-socks5-tunnel)

**Risk: Memory safety issues in native code**

| Property | Detail |
|----------|--------|
| Language | C |
| Dependencies | lwIP, libyaml, hev-task-system |
| JNI surface | 3 methods: start, stop, getStats |
| Input | TUN file descriptor (kernel-managed), YAML config file |

**Analysis:** The native library processes raw IP packets from the TUN interface. As C code, it is susceptible to memory safety issues (buffer overflows, use-after-free). However:
- The library is widely used (hev-socks5-tunnel has many users across multiple Android apps)
- The JNI interface is minimal (3 methods), limiting the attack surface from the Java/Kotlin side
- The TUN file descriptor is provided by the Android VPN framework, which validates permissions
- The YAML config file is generated by the app itself (not user-supplied)

### 5. Bluetooth RFCOMM transport

**Risk: Unauthorized Bluetooth connections**

| Property | Detail |
|----------|--------|
| Protocol | RFCOMM (serial port emulation) |
| UUID | `a1b2c3d4-e5f6-7890-abcd-ef1234567890` |
| Authentication | Bluetooth pairing (system-level) |
| Encryption | Bluetooth link-layer encryption (if paired) |

**Analysis:** Bluetooth connections require device pairing, which provides a level of authentication that Wi-Fi Direct does not. However, the service UUID is hardcoded and discoverable via SDP (Service Discovery Protocol). A paired device could connect to the RFCOMM service and bridge to the SOCKS5 server.

The Bluetooth server bridges connections directly to `localhost:1080` (the loopback SOCKS5 server). There is no additional authentication at the application level.

## Debug signing

Both release APKs are currently signed with the debug keystore (`signingConfig = signingConfigs.getByName("debug")` in both `app/build.gradle.kts` and `client/build.gradle.kts`). This means:

- APKs can be resigned by anyone with the (well-known) Android debug key
- The `release.yml` workflow supports proper release signing via GitHub secrets (`ANDROID_SIGNING_KEY`, `ANDROID_KEY_ALIAS`, etc.), but falls back to unsigned APKs if secrets are not configured
- Debug-signed APKs cannot be uploaded to the Google Play Store
- Users should verify APK checksums from GitHub Releases rather than relying on signature verification

**Recommendation:** For production distribution, configure release signing with a private keystore.

## Carrier detection considerations

The app is designed to avoid common tethering detection methods:

| Detection method | How it is addressed |
|-----------------|---------------------|
| TTL analysis | Outbound sockets bound to cellular `Network` -- packets originate from the phone's process with native TTL |
| APN-based | Wi-Fi Direct does not use the tethering APN |
| DPI (Deep Packet Inspection) | SOCKS5 traffic looks like normal TCP from the host. HTTPS traffic is end-to-end encrypted. |
| DNS inspection | DNS queries come from the host phone's process, not a tethered client |
| User-Agent sniffing | Not addressed -- if a client sends desktop browser headers, the carrier could detect non-phone traffic |

**Note:** Carrier tethering detection methods vary by carrier and region. This app does not guarantee undetectable tethering. Using the app may violate carrier terms of service.

## Security recommendations

1. **Do not expose the SOCKS5 server to untrusted networks.** The server has no authentication and is designed for use on an isolated P2P link.

2. **Use HTTPS for sensitive traffic.** The SOCKS5 tunnel does not encrypt traffic. HTTPS provides end-to-end encryption between the client app and the destination server, regardless of the proxy path.

3. **Use a strong passphrase.** A random passphrase is generated on first launch. You can change it in the host app UI before starting the hotspot. Choose a passphrase that is not easily guessable.

4. **Review Bluetooth pairing.** The Bluetooth transport relies on system-level pairing for authentication. Ensure only trusted devices are paired with the host phone.

5. **Keep hev-socks5-tunnel updated.** The native library processes raw network packets in C. Update the git submodule periodically to pick up security fixes.

## Reporting security issues

If you discover a security vulnerability, please report it via GitHub's private vulnerability reporting feature on the [FlossWare/hotspot-android](https://github.com/FlossWare/hotspot-android) repository rather than opening a public issue.