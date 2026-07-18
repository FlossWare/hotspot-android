# Threat Model

Issue: [#29](https://github.com/FlossWare/hotspot-android/issues/29) -- Threat model and security review needed before public release.

Last updated: 2026-07-18

---

## 1. System Overview

FlossWare Hotspot is a two-app system that shares a host device's mobile data with a client device, bypassing carrier tethering restrictions. The host creates an isolated peer-to-peer network and runs proxy services; the client tunnels all traffic through the host.

### Architecture and Data Flow

```
                          INTERNET
                             |
                    [ Mobile Carrier ]
                             |
                     (Cellular Network)
                             |
  +---------------------------------------------------------------------+
  |  HOST DEVICE                                                        |
  |                                                                     |
  |  NetworkManager                                                     |
  |   - Binds sockets to cellular Network                               |
  |   - Provides SocketFactory for outbound connections                  |
  |   - Detects upstream DNS from LinkProperties                        |
  |                                                                     |
  |  ProxyManager                                                       |
  |   +---------------------------+   +-----------------------------+   |
  |   | Socks5Server              |   | Socks5Server (loopback)     |   |
  |   | bind: 192.168.49.1:1080   |   | bind: 127.0.0.1:1080       |   |
  |   | SSRF protection: ON       |   | SSRF protection: ON        |   |
  |   | Auth: OFF                 |   | Auth: OFF                  |   |
  |   +---------------------------+   +-----------------------------+   |
  |                                      ^              ^               |
  |   +---------------------------+      |              |               |
  |   | DnsRelay                  |   +--------+   +-----------+        |
  |   | bind: 192.168.49.1:5353   |   | BT     |   | USB       |       |
  |   | upstream: carrier DNS     |   | Server |   | Server    |       |
  |   +---------------------------+   +--------+   +-----------+        |
  |                                   RFCOMM        Accessory           |
  |   +---------------------------+      |          protocol            |
  |   | HttpCache (in-memory)     |      |              |               |
  |   | max 50 MB, per-entry 5 MB |      |              |               |
  |   +---------------------------+      |              |               |
  +--------|-------------|---------------|--------------|-----------+   |
           |             |               |              |               |
     Wi-Fi Direct    Wi-Fi Direct    Bluetooth        USB cable         |
     (WPA2 link)     (WPA2 link)     (paired)        (physical)        |
           |             |               |              |               |
  +--------|-------------|---------------|--------------|-----------+   |
  |  CLIENT DEVICE       |                                          |   |
  |                      v                                          |   |
  |   +---------------------------+                                 |   |
  |   | VpnService (tun0)        |                                  |   |
  |   | Captures all app traffic |                                  |   |
  |   +---------------------------+                                 |   |
  |              |                                                  |   |
  |   +---------------------------+                                 |   |
  |   | hev-socks5-tunnel (C/JNI)|                                  |   |
  |   | IP packets -> SOCKS5     |                                  |   |
  |   +---------------------------+                                 |   |
  +-------------------------------------------------------------+      |
```

### Transport Summary

| Transport    | Link Encryption        | App-Layer Auth      | Entry Point            |
|-------------|------------------------|---------------------|------------------------|
| Wi-Fi Direct | WPA2 (passphrase)      | None (SOCKS5 open)  | 192.168.49.1:1080/5353 |
| Bluetooth    | BT link-layer          | Pairing + bond check| RFCOMM UUID            |
| USB          | None (wired)           | Physical access     | Accessory protocol     |

---

## 2. Trust Boundaries

A trust boundary is a point where data crosses between components with different privilege levels. Untrusted data enters the system at these locations:

### TB-1: Wi-Fi Direct Network Boundary

Any device that knows the passphrase can join the Wi-Fi Direct group. On Android 10+ (API 29+), the passphrase is user-configurable via the host app UI (a random passphrase is generated on first launch and stored in SharedPreferences). On older Android versions, the system generates a random passphrase per session.

**What crosses this boundary:**
- SOCKS5 CONNECT requests from any device on the 192.168.49.x subnet
- DNS queries from any device on the subnet
- Wi-Fi Direct management frames

### TB-2: SOCKS5 Proxy Ingress

The SOCKS5 server (`Socks5Server.kt`) accepts TCP connections on 192.168.49.1:1080 from any device on the P2P network. A second instance binds to 127.0.0.1:1080 for Bluetooth and USB bridge traffic.

**What crosses this boundary:**
- SOCKS5 protocol negotiation (version, auth method, command, address, port)
- All client application-layer data (HTTP, HTTPS, etc.) relayed through the tunnel

**Current security controls:**
- SSRF protection blocks connections to loopback (127.0.0.0/8) and link-local (169.254.0.0/16, fe80::/10) addresses (`isBlockedDestination()`, line 575-577)
- Only CONNECT command supported; BIND and UDP ASSOCIATE return `REPLY_CMD_NOT_SUPPORTED`
- Per-client connection limit: 10 (configurable via `maxConnectionsPerClient`)
- Total connection limit: 100 (configurable via `maxTotalConnections`)
- Socket read/write timeout: 60 seconds
- Thread pool bounded at 32 threads with `CallerRunsPolicy` backpressure
- Auth support EXISTS in code but is NOT enabled -- `ProxyManager.kt` creates instances without passing username/password (lines 45-57)

### TB-3: DNS Relay Ingress

The DNS relay (`DnsRelay.kt`) listens on 192.168.49.1:5353 (UDP) and forwards queries to the carrier's DNS or Google's 8.8.8.8 as fallback.

**What crosses this boundary:**
- DNS query packets from any device on the P2P network
- DNS response packets from upstream resolvers

**Current security controls:**
- Transaction ID validation: response bytes [0:1] must match query bytes [0:1] (lines 158-161)
- Cache keyed on question section (bytes from offset 2), excluding transaction ID
- TTL clamped to 10s-3600s range
- Cache limited to 1000 entries with eviction
- 5-second upstream query timeout
- Thread pool bounded at 8 threads

### TB-4: HTTP Cache

The HTTP cache (`HttpCache.kt`) stores and serves plaintext HTTP (port 80) responses within the SOCKS5 proxy.

**What crosses this boundary:**
- HTTP request lines and headers parsed from the SOCKS5 data stream
- HTTP response bodies stored in memory

**Current security controls:**
- Only caches GET requests with 200 OK responses
- Respects `Cache-Control: no-store`, `no-cache`, `private`
- Skips responses with `Set-Cookie` headers
- Skips responses with `Vary` headers
- Bypasses cache for requests with `Authorization` headers
- Respects `Pragma: no-cache` in both request and response
- Does not cache `max-age=0` responses
- Content-type whitelist: `text/*`, `application/javascript`, `application/json`, `image/*`
- Per-entry max: 5 MB; total max: 50 MB
- HTTP line length limit: 8192 bytes
- Response header count limit: 100

### TB-5: Bluetooth RFCOMM

The Bluetooth server (`BluetoothServer.kt`) listens for RFCOMM connections using a fixed UUID (`a1b2c3d4-e5f6-7890-abcd-ef1234567890`) and bridges them to the loopback SOCKS5 server.

**What crosses this boundary:**
- Raw byte stream from paired Bluetooth device, forwarded directly to SOCKS5

**Current security controls:**
- Rejects unbonded devices: `bondState != BluetoothDevice.BOND_BONDED` check (line 132)
- Requires Android Bluetooth pairing (PIN or passkey exchange)
- On Android 12+ (API 31+), requires `BLUETOOTH_CONNECT` and `BLUETOOTH_ADVERTISE` runtime permissions
- Uses `listenUsingRfcommWithServiceRecord()` which encrypts the link (standard RFCOMM security)

### TB-6: USB Accessory

The USB server (`UsbServer.kt`) relays data from a physically connected USB accessory to the loopback SOCKS5 server.

**What crosses this boundary:**
- Raw byte stream from USB accessory, forwarded directly to SOCKS5

**Current security controls:**
- Requires physical cable connection
- On Android 13+ (API 33+), broadcast receiver registered with `RECEIVER_NOT_EXPORTED` (line 90)
- Android USB accessory permission prompt shown to user
- No software-level authentication (physical access is the trust boundary)

---

## 3. Attack Vectors and Mitigations

### AV-1: SSRF via SOCKS5 Proxy

| Property | Detail |
|----------|--------|
| **Threat** | An attacker on the P2P network requests the SOCKS5 proxy to connect to internal addresses (127.0.0.1, 169.254.x.x, 10.x.x.x) to access services on the host device or host's LAN |
| **Severity** | HIGH |
| **Current mitigation** | `isBlockedDestination()` in `Socks5Server.kt` (line 575) blocks loopback (`isLoopbackAddress`) and link-local (`isLinkLocalAddress`) addresses. SSRF protection is enabled by default (`ssrfProtection = true`) |
| **Residual risk** | **Private network addresses (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16) are NOT blocked** except link-local. If the host is connected to a home or corporate Wi-Fi in addition to cellular, an attacker could potentially reach devices on that network. The `socketFactoryProvider` returns the cellular `Network.socketFactory` which routes through cellular, but DNS resolution may still leak internal hostnames. Also, `InetAddress.isSiteLocalAddress()` is not checked |

### AV-2: DNS Cache Poisoning

| Property | Detail |
|----------|--------|
| **Threat** | An attacker injects forged DNS responses to redirect client traffic to malicious servers |
| **Severity** | MEDIUM |
| **Current mitigation** | Transaction ID validation ensures response bytes [0:1] match the query (lines 158-161 of `DnsRelay.kt`). Cache keys exclude the transaction ID, preventing simple ID-guessing attacks. Upstream query sockets are ephemeral (random source port per query) |
| **Residual risk** | No DNSSEC validation. No 0x20 encoding (random case in query names for response verification). The transaction ID is only 16 bits (65,536 possibilities) -- a determined attacker who can race the upstream response could succeed with ~131K attempts (birthday attack). Source port randomization depends on the system `DatagramSocket` implementation. No rate limiting on DNS queries |

### AV-3: DoS via Connection Flooding

| Property | Detail |
|----------|--------|
| **Threat** | An attacker opens many connections to exhaust the proxy's thread pool, memory, or file descriptors |
| **Severity** | MEDIUM |
| **Current mitigation** | Per-client limit of 10 connections and total limit of 100 connections (`Socks5Server.kt` lines 68-79). Thread pool capped at 32 threads with `CallerRunsPolicy` providing backpressure. 60-second socket timeout prevents indefinite connection holding |
| **Residual risk** | DNS relay has no per-client rate limiting -- an attacker could flood UDP queries (4-8 threads, bounded queue of 32 with `CallerRunsPolicy`). The HTTP `ProxyServer` has no connection limits at all (though it may not be exposed on the P2P interface in normal operation). An attacker could establish 10 connections, send minimal data to keep them alive, then reconnect from a spoofed source IP to bypass per-client limits (though IP spoofing on Wi-Fi Direct is non-trivial) |

### AV-4: Credential Brute Force on SOCKS5 Auth

| Property | Detail |
|----------|--------|
| **Threat** | An attacker repeatedly attempts SOCKS5 username/password authentication to guess credentials |
| **Severity** | LOW (auth is currently disabled) |
| **Current mitigation** | `constantTimeEquals()` in `Socks5Server.kt` (line 579) prevents timing side-channel attacks. Comparison XORs all bytes and checks the result, preventing early-exit information leakage |
| **Residual risk** | **Authentication is not enabled.** `ProxyManager.kt` creates `Socks5Server` instances without setting `username` or `password`, so `requireAuth` is false and the server uses `AUTH_NONE`. If auth were enabled, there is no brute-force rate limiting, no account lockout, and no failed-attempt logging beyond a single `Log.w` per failure. The `constantTimeEquals` function returns `false` immediately when lengths differ (line 583), which leaks password length via timing -- though this is a minor concern since SOCKS5 usernames are typically short |

### AV-5: HTTP Cache Poisoning

| Property | Detail |
|----------|--------|
| **Threat** | An attacker poisons the HTTP cache so subsequent clients receive malicious content |
| **Severity** | LOW |
| **Current mitigation** | Cache only stores GET 200 OK responses. Respects `Cache-Control` directives. Does not cache `Set-Cookie` or `Vary` responses. Bypasses cache for `Authorization` requests. Content-type whitelist limits what gets cached. Per-entry and total size limits prevent memory exhaustion |
| **Residual risk** | The cache is limited to plaintext HTTP (port 80), which is increasingly rare. An attacker who controls an upstream HTTP server (or performs a MITM on the cellular connection) could serve poisoned responses that get cached and served to other clients on the P2P network. However, the practical impact is low because: (a) most modern traffic is HTTPS, (b) cached entries expire per TTL, and (c) the attack requires control of the upstream response. The cache key is `"GET $host$path"` which does not include query parameters -- different query strings to the same path would share a cache entry |

### AV-6: Man-in-the-Middle on Wi-Fi Direct

| Property | Detail |
|----------|--------|
| **Threat** | An attacker intercepts traffic between client and host on the Wi-Fi Direct link |
| **Severity** | MEDIUM |
| **Current mitigation** | Wi-Fi Direct uses WPA2 encryption at the link layer. The host is always the group owner (192.168.49.1), and the passphrase must be known to join |
| **Residual risk** | On Android 10+, the passphrase is user-configurable and displayed in the UI and shared via QR code. Anyone with the passphrase can join the group and passively observe all SOCKS5 traffic, since the proxy tunnel itself is unencrypted. WPA2 encrypts link-layer frames but all group members share the same key, so a member can decrypt other members' traffic. HTTPS traffic remains protected end-to-end, but DNS queries, plaintext HTTP, and SOCKS5 protocol headers are visible to any group member. The passphrase is stored in SharedPreferences (app-private, not encrypted at rest) |

### AV-7: Bluetooth Pairing Attacks

| Property | Detail |
|----------|--------|
| **Threat** | An attacker pairs with the host device to gain access to the SOCKS5 proxy via Bluetooth |
| **Severity** | LOW |
| **Current mitigation** | `BluetoothServer.kt` rejects unbonded devices (line 132). Pairing requires user interaction (PIN confirmation or passkey entry) on both devices. Android runtime permissions (BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE) gate access on API 31+ |
| **Residual risk** | A previously-paired device (e.g., a friend's phone that was paired for file transfer) could discover the RFCOMM service UUID via SDP and connect to the proxy without the host user's knowledge. There is no per-session authorization, connection limit, or notification when a Bluetooth client connects via RFCOMM. The service UUID is hardcoded and predictable. The `BluetoothServer.start()` method does not whitelist specific device MAC addresses |

### AV-8: Malicious USB Accessory

| Property | Detail |
|----------|--------|
| **Threat** | A malicious USB device exploits the accessory protocol to access the SOCKS5 proxy or inject malformed data |
| **Severity** | LOW |
| **Current mitigation** | Physical connection required. Android shows a permission dialog before granting USB accessory access. On API 33+, broadcast receiver uses `RECEIVER_NOT_EXPORTED` to prevent spoofed intents |
| **Residual risk** | Once the user grants USB accessory permission, the device has unrestricted byte-stream access to the SOCKS5 proxy. A malicious accessory could attempt protocol-level attacks (malformed SOCKS5 packets, resource exhaustion). There is no validation of the accessory's manufacturer/model against an allowlist. The relay between USB and SOCKS5 is a raw byte-stream bridge with no filtering or inspection |

### AV-9: Data Exfiltration via Proxy Logs

| Property | Detail |
|----------|--------|
| **Threat** | Log exports inadvertently expose sensitive data (passwords, destinations visited, credentials) |
| **Severity** | MEDIUM |
| **Current mitigation** | `LogExporter.kt` applies regex-based sanitization (lines 22-35) that strips passwords, passphrases, PSKs, usernames, credentials, secrets, tokens, and API keys. Logs are written to the app's private cache directory. Old exports are auto-cleaned (keeps 5 most recent). Export requires explicit user action (share button) |
| **Residual risk** | Sanitization is regex-based and may miss novel patterns. SOCKS5 debug logs (`debugMode = true`) include destination hostnames and IP addresses in cleartext, revealing the user's browsing history. The `Log.w` calls in `Socks5Server.kt` log client IP addresses and destination hosts even when `debugMode` is false (e.g., "SSRF blocked: CONNECT to $host:$port ($resolved) from $clientAddr" at line 360). Bluetooth device names and MAC addresses are logged. DNS query source addresses are logged. The app uses `android.util.Log` directly in many places (not `LogCollector`), so those entries appear in the system logcat and are accessible to any app with `READ_LOGS` permission or via ADB |

### AV-10: WakeLock Abuse / Battery Drain

| Property | Detail |
|----------|--------|
| **Threat** | An attacker keeps the proxy busy to prevent the host from entering power-saving mode, draining the battery |
| **Severity** | LOW |
| **Current mitigation** | WakeLock has a 4-hour maximum timeout (`WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L` in `HotspotService.kt` line 285). The service releases the WakeLock after 15 consecutive polls (30 seconds at 2-second intervals) of zero traffic change, then switches to a 10-second poll interval. WakeLock is re-acquired when traffic resumes |
| **Residual risk** | An attacker sending a trickle of data (even 1 byte per 2-second poll window) would keep the WakeLock held and the device awake. The 4-hour timeout provides an absolute ceiling, but the attack can be sustained by reconnecting. The service runs as a foreground service, which already keeps the process alive independent of the WakeLock. Battery drain is an inherent consequence of sharing mobile data |

---

## 4. Data Flow Security

### What is encrypted

| Segment | Encryption | Key Management |
|---------|-----------|----------------|
| Wi-Fi Direct link layer | WPA2-PSK | User-configurable passphrase (API 29+) or system-generated |
| HTTPS traffic through proxy | TLS (end-to-end) | Managed by client apps and destination servers |
| Bluetooth link layer | BT encryption (E0/AES-CCM) | Managed by Android Bluetooth stack |

### What is plaintext

| Segment | What is visible |
|---------|----------------|
| SOCKS5 protocol between client and host | SOCKS5 handshake, CONNECT destination (host:port), relayed plaintext HTTP |
| DNS queries/responses | Full query name, response records, source/destination IPs |
| HTTP traffic through proxy (port 80) | Full request/response including headers and body |
| USB transport | Raw byte stream -- no link-layer encryption |
| HTTP cache contents | Stored response bodies and headers (in host process memory) |

### Key observation

The SOCKS5 tunnel itself is **not encrypted**. HTTPS traffic passing through the tunnel remains protected end-to-end, but the SOCKS5 protocol metadata (which host and port the client is connecting to) is visible to any observer on the Wi-Fi Direct link. DNS queries are fully visible.

---

## 5. Credential Management

### Wi-Fi Direct Passphrase

| Property | Detail |
|----------|--------|
| Storage | SharedPreferences (`hotspot_prefs`, key `wifi_direct_passphrase`); random default generated on first launch |
| Transmission | Displayed in host app UI; optionally shared via QR code |
| Rotation | User can change the passphrase in the host app UI before starting the hotspot |
| Scope | Per-installation -- each device generates its own random passphrase on first launch |

### SOCKS5 Credentials

| Property | Detail |
|----------|--------|
| Storage | Constructor parameters on `Socks5Server` (in-memory only) |
| Current state | **Not enabled.** `ProxyManager` creates servers without credentials |
| Auth protocol | RFC 1929 username/password subnegotiation |
| Comparison | Constant-time XOR (`constantTimeEquals`) with length-leak caveat |

### Bluetooth Pairing

| Property | Detail |
|----------|--------|
| Storage | Managed by Android Bluetooth stack (system keystore) |
| Validation | `BluetoothDevice.BOND_BONDED` checked before accepting connections |
| Revocation | User must unpair device via Android Bluetooth settings |

### Summary of Credential Risks

1. The Wi-Fi Direct passphrase is per-installation (random default) and user-configurable. An attacker who sees the UI or scans the QR code knows the passphrase for that specific instance, but not for other installations.
2. SOCKS5 authentication is implemented but disabled. There is no defense-in-depth at the proxy layer.
3. The passphrase persists across sessions (stored in SharedPreferences). Consider adding per-session rotation as a future option.

---

## 6. Privacy Considerations

### Data Collected by the App

| Data | Where | Purpose | Retention |
|------|-------|---------|-----------|
| Connected device MAC addresses | In-memory (`CopyOnWriteArrayList`) | UI display, connection tracking | Cleared on service stop |
| Connected device names | In-memory (Bluetooth/Wi-Fi Direct) | UI display | Cleared on service stop |
| Destination hostnames/IPs | `android.util.Log` (logcat) | Debug diagnostics | Until logcat buffer rotates |
| Bytes transferred (aggregate) | In-memory counter (`AtomicLong`) | UI statistics | Cleared on service stop |
| DNS queries | In-memory cache (1000 entries) | Performance optimization | TTL-based expiration; cleared on stop |
| HTTP responses | In-memory cache (50 MB max) | Performance optimization | TTL-based expiration; cleared on stop |

### Log Sanitization

The `LogExporter` sanitizes the following patterns before writing to file:

- `password=`, `passphrase=`, `passwd=`, `psk=` -- replaced with `***`
- `username=`, `user=` -- replaced with `***`
- `credential=`, `secret=`, `token=`, `api_key=` -- replaced with `***`
- `auth* succeeded/failed for user '...'` -- username replaced with `***`
- `P:...` (WPA passphrase in Wi-Fi config strings) -- replaced with `P:***`

### Privacy Gaps

1. **Destination logging:** SOCKS5 server logs destination host:port for SSRF blocks, connection failures, and debug messages. These entries reach the system logcat before `LogExporter` sanitization and reveal browsing activity.
2. **MAC addresses:** Wi-Fi Direct and Bluetooth device MAC addresses are logged and displayed in the UI. These are persistent device identifiers.
3. **Not all logging goes through LogCollector:** Many components use `android.util.Log` directly rather than `LogCollector`, so their output is not captured in the ring buffer for sanitized export, but IS visible in system logcat.
4. **No data-at-rest encryption:** The log export files in the cache directory are not encrypted. They are protected by Android's app sandbox but could be accessed via ADB or by a root-privileged app.

---

## 7. Recommendations

### P0 -- Critical (address before public release)

1. ~~**Randomize the Wi-Fi Direct passphrase per session.**~~ **DONE.** The passphrase is now user-configurable with a cryptographically random default generated on first launch. Consider adding an option to regenerate the passphrase automatically on each service restart for maximum security.

2. **Enable SOCKS5 authentication by default.** Generate a random username/password pair per session and display them alongside the passphrase. The authentication code already exists in `Socks5Server.kt` -- `ProxyManager` just needs to pass credentials.

3. **Block all private network ranges in SSRF protection.** Extend `isBlockedDestination()` to block site-local addresses (`isSiteLocalAddress`), not just loopback and link-local. This prevents an attacker from reaching the host's home/corporate LAN through the proxy.

### P1 -- High (address in near-term releases)

4. **Add TLS support for the SOCKS5 tunnel.** Wrap the SOCKS5 server socket in TLS (`SSLServerSocket`) to encrypt the proxy tunnel independently of Wi-Fi Direct link-layer encryption. This provides defense-in-depth against group members sniffing traffic.

5. **Add DNS rate limiting.** Implement per-client rate limiting on the DNS relay (e.g., 50 queries/second per source IP) to prevent DNS flooding attacks.

6. **Fix `constantTimeEquals` length leak.** The current implementation returns `false` immediately when password lengths differ (line 583), leaking the password length via timing. Pad both inputs to the same length before comparison, or always compare a fixed number of bytes using the longer input's length.

7. **Add brute-force protection for SOCKS5 auth.** If authentication is enabled, implement a failed-attempt counter per source IP with exponential backoff or temporary blocking after N failures.

8. **Route all logging through LogCollector.** Replace direct `android.util.Log` calls with `LogCollector` calls so all log output goes through the ring buffer and benefits from sanitization on export. Avoid logging destination hostnames/IPs except in debug mode.

### P2 -- Medium (address in future releases)

9. **Add DNS-over-HTTPS (DoH) support.** Forward DNS queries to a DoH resolver (e.g., Cloudflare 1.1.1.1, Google 8.8.8.8) instead of plaintext UDP to prevent DNS interception on the cellular link.

10. **Bluetooth device whitelisting.** Allow the user to select which bonded devices are permitted to connect via RFCOMM, rather than accepting all bonded devices.

11. **USB accessory validation.** Check the accessory's manufacturer and model strings against an expected value before establishing the relay.

12. **Add per-session credential rotation.** Automatically regenerate SOCKS5 credentials and Wi-Fi passphrase on each service restart, invalidating previous credentials.

13. **Encrypt log exports.** Encrypt the exported log file or use Android's `EncryptedFile` API before writing to the cache directory.

14. **Add HTTP cache key query parameters.** Include the full URL (with query string) in the cache key to prevent cache collisions across different resources on the same path.

### P3 -- Low (nice to have)

15. **Certificate pinning for upstream connections.** Consider pinning certificates for known high-value destinations (though this conflicts with the transparent proxy model).

16. **Connection notifications for Bluetooth.** Show a notification or UI indicator when a new Bluetooth device connects via RFCOMM, so the user is aware of proxy usage.

17. **DNSSEC validation.** Validate DNSSEC signatures on DNS responses before caching (significant implementation complexity).

18. **Audit hev-socks5-tunnel.** Perform or commission a security audit of the native C library that processes raw IP packets from the TUN interface, as memory safety vulnerabilities there could be exploited by any app on the client device.

---

## Appendix: Source File Reference

| Component | File | Key Security Code |
|-----------|------|-------------------|
| SOCKS5 Server | `app/.../proxy/Socks5Server.kt` | `isBlockedDestination()` (L575), `constantTimeEquals()` (L579), connection limits (L68-79), auth negotiation (L255-299) |
| DNS Relay | `app/.../proxy/DnsRelay.kt` | Transaction ID validation (L158-161), cache key extraction (L192-199), TTL clamping (L251) |
| HTTP Cache | `app/.../proxy/HttpCache.kt` | Cache-Control parsing (L155-168), content-type filter (L226-232), size limits (L270-271) |
| HTTP Proxy | `app/.../proxy/ProxyServer.kt` | No auth, no SSRF protection, no connection limits |
| Bluetooth | `app/.../service/BluetoothServer.kt` | Bond state check (L132), RFCOMM relay to loopback (L154) |
| USB | `app/.../service/UsbServer.kt` | `RECEIVER_NOT_EXPORTED` (L90), relay to loopback (L148) |
| Wi-Fi Direct | `app/.../service/WifiDirectManager.kt` | Configurable passphrase, group creation |
| Log Sanitizer | `app/.../log/LogExporter.kt` | Regex patterns (L22-35), `sanitize()` (L40-46) |
| Service Lifecycle | `app/.../service/HotspotService.kt` | WakeLock management (L215-235), idle detection (L93-109) |
| Proxy Manager | `app/.../service/ProxyManager.kt` | Server instantiation without auth (L45-57) |
| Network Manager | `app/.../service/NetworkManager.kt` | Cellular binding (L38-39), upstream DNS detection (L57-58) |
