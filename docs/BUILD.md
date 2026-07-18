# Build Guide

Detailed instructions for building FlossWare Hotspot from source. For a quick overview, see the [README](../README.md#building).

## Prerequisites

### Required Software

| Software | Version | Purpose |
|----------|---------|---------|
| JDK | 21 | Kotlin/Gradle compilation (Temurin recommended) |
| Android SDK | API 35 (compileSdk) | Android platform libraries |
| Android NDK | **27.0.12077973** | Native C library compilation (hev-socks5-tunnel) |
| Git | any | Source control with submodule support |

### Recommended

| Software | Purpose |
|----------|---------|
| Android Studio | IDE with built-in SDK/NDK management |
| `adb` | Deploy APKs to devices and collect logs |

## Clone the Repository

The client module depends on [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel), a native C library included as a git submodule. You must initialize it before building.

```bash
# Clone with submodules in one step
git clone --recursive https://github.com/FlossWare/hotspot-android.git
cd hotspot-android
```

If you already cloned without `--recursive`:

```bash
git submodule update --init --recursive
```

Verify the submodule is present:

```bash
ls client/src/main/jni/hev-socks5-tunnel/src/
# Should list source files, not be empty
```

## NDK Setup

The NDK is required because the client module compiles hev-socks5-tunnel via `ndk-build` for four ABIs.

### Why the Exact Version Matters

The `client/build.gradle.kts` specifies `ndkVersion = "27.0.12077973"`. Gradle will fail if a different NDK version is installed. This ensures reproducible native builds across all contributors and CI.

### Installing via Android Studio

1. Open **Settings** (or **Preferences** on macOS)
2. Navigate to **Languages & Frameworks > Android SDK > SDK Tools**
3. Check **Show Package Details**
4. Under **NDK (Side by side)**, select **27.0.12077973**
5. Click **Apply**

### Installing via Command Line

```bash
sdkmanager "ndk;27.0.12077973"
```

Verify installation:

```bash
ls $ANDROID_HOME/ndk/27.0.12077973/
```

### ABI Filters

The native library is compiled for all four Android ABIs:

| ABI | Architecture | Devices |
|-----|-------------|---------|
| `arm64-v8a` | ARM 64-bit | Most modern phones and tablets |
| `armeabi-v7a` | ARM 32-bit | Older phones |
| `x86_64` | Intel/AMD 64-bit | Emulators, Chromebooks |
| `x86` | Intel/AMD 32-bit | Older emulators |

To build for a single ABI (faster iteration):

```bash
./gradlew :client:assembleDebug -Pabi=arm64-v8a
```

Or modify `client/build.gradle.kts` temporarily:

```kotlin
ndk {
    abiFilters += listOf("arm64-v8a")  // Only build for your test device
}
```

## Building

### Debug APKs

```bash
./gradlew assembleDebug
```

Output locations:

| APK | Path |
|-----|------|
| Host | `app/build/outputs/apk/debug/app-debug.apk` |
| Client | `client/build/outputs/apk/debug/client-debug.apk` |

### Release APKs

```bash
./gradlew assembleRelease
```

Release builds enable ProGuard/R8 minification (`isMinifyEnabled = true`). Without signing configuration, the output will be unsigned APKs.

Output locations:

| APK | Path |
|-----|------|
| Host | `app/build/outputs/apk/release/app-release-unsigned.apk` |
| Client | `client/build/outputs/apk/release/client-release-unsigned.apk` |

### Build a Single Module

```bash
# Host only
./gradlew :app:assembleDebug

# Client only (requires NDK)
./gradlew :client:assembleDebug
```

## Running Tests

### Unit Tests

```bash
# All tests
./gradlew test

# Host tests only
./gradlew :app:test

# Client tests only
./gradlew :client:test
```

Test reports are generated at:
- `app/build/reports/tests/testDebugUnitTest/index.html`
- `client/build/reports/tests/testDebugUnitTest/index.html`

### What the Tests Cover

The proxy and model code has no Android framework dependencies, so tests run on the JVM without an emulator:

- `Socks5ServerTest` -- SOCKS5 protocol handling, CONNECT command, error cases
- `DnsRelayTest` -- DNS forwarding, cache behavior, TTL extraction
- `HttpCacheTest` -- HTTP response caching logic
- `ProxyServerTest` -- Proxy abstraction
- `WifiDirectManagerTest` -- Failure reason mapping
- `HotspotStateTest`, `ConnectedDeviceTest`, `VpnStateTest` -- Data model validation
- `SocksTunnelTest` -- YAML config generation, DNS address constants

### Lint

```bash
./gradlew lint
```

Lint results: `app/build/reports/lint-results-debug.html`

## Signing Release Builds

Both modules default to the debug keystore for release builds (`signingConfig = signingConfigs.getByName("debug")`). For production distribution, configure a release signing key.

### Local Signing

Create a `keystore.properties` file in the project root (do **not** commit this):

```properties
storeFile=/path/to/your/release-keystore.jks
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

Then update `app/build.gradle.kts` and `client/build.gradle.kts` to read from it.

### CI Signing

The `release.yml` workflow supports signing via GitHub secrets:

| Secret | Purpose |
|--------|---------|
| `ANDROID_SIGNING_KEY` | Base64-encoded keystore file |
| `ANDROID_KEY_ALIAS` | Key alias within the keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_PASSWORD` | Key password |

If these secrets are not configured, the release workflow produces unsigned APKs.

## Testing on Devices and Emulators

### Physical Devices (Recommended)

Wi-Fi Direct and Bluetooth require real hardware. Install both APKs on two devices:

```bash
# Install host on phone A
adb -s <device-A-serial> install app/build/outputs/apk/debug/app-debug.apk

# Install client on phone B
adb -s <device-B-serial> install client/build/outputs/apk/debug/client-debug.apk
```

### Emulators

Emulators have significant limitations for this project:

| Feature | Emulator Support |
|---------|-----------------|
| Wi-Fi Direct | Not supported |
| Bluetooth | Not supported |
| VPN (VpnService) | Supported |
| SOCKS5 proxy | Supported (localhost only) |

You can test the SOCKS5 proxy and VPN tunnel logic on emulators by manually connecting to `localhost:1080`, but end-to-end testing of the Wi-Fi Direct or Bluetooth transports requires physical devices.

### Collecting Logs

```bash
# All hotspot-related logs
adb logcat -s HotspotService Socks5Server DnsRelay WifiDirectManager BluetoothServer

# Client-side logs
adb logcat -s TunnelService SocksTunnel BluetoothTunnel
```

## Build System Details

| Property | Value |
|----------|-------|
| Build system | Gradle with Kotlin DSL |
| Gradle wrapper | See `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.1.0 |
| Compose BOM | 2024.12.01 |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 35 |
| Compile SDK | 35 |
| Java compatibility | 17 |
| Native build system | `ndk-build` (Android.mk), not CMake |

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `No version of NDK matched the requested version` | NDK 27.0.12077973 not installed | `sdkmanager "ndk;27.0.12077973"` |
| `Android.mk: No such file or directory` | Submodule not initialized | `git submodule update --init --recursive` |
| Empty `client/src/main/jni/hev-socks5-tunnel/` | Cloned without `--recursive` | `git submodule update --init --recursive` |
| `CMake` or `ninja` errors | Wrong build system assumption | This project uses `ndk-build`, not CMake |
| Native build failures on macOS | Missing toolchain | `xcode-select --install` |
| JDK version mismatch | Wrong JDK | Requires JDK 21 (Temurin recommended) |
| `Could not resolve` dependencies | Missing repositories | Ensure internet access; check `settings.gradle.kts` |
