# OEM Compatibility Matrix

This document tracks device compatibility for FlossWare Hotspot across Android OEMs, API levels, and hardware variants.

## Test Matrix

| Vendor | Model | FTL Device ID | API Level | Wi-Fi Direct | VPN Service | Foreground Service | Status | Known Issues |
|--------|-------|---------------|-----------|:------------:|:-----------:|:------------------:|:------:|--------------|
| Google | Pixel 5 | `redfin` | 30 (R) | Pass | Pass | Pass | Verified | None |
| Google | Pixel 6 | `oriole` | 33 (T) | Pass | Pass | Pass | Verified | None |
| Google | Pixel 7a | `lynx` | 34 (U) | Pass | Pass | Pass | Verified | None |
| Samsung | Galaxy S22 Ultra | `b0q` | 33 (T) | Pass | Pass | Pass | Verified | See [Samsung Notes](#samsung) |
| Samsung | Galaxy S23 | `dm1q` | 34 (U) | Pass | Pass | Pass | Verified | See [Samsung Notes](#samsung) |
| Samsung | Galaxy A14 | `a14xm` | 33 (T) | -- | -- | -- | Untested | Budget device, Wi-Fi Direct may be limited |
| Xiaomi | Redmi Note 12 | -- | 33 (T) | -- | -- | -- | Untested | MIUI aggressive battery management |
| OnePlus | 11 | -- | 34 (U) | -- | -- | -- | Untested | OxygenOS background restrictions |

**Legend:**
- Pass: All tests pass on Firebase Test Lab
- Fail: One or more tests fail
- `--`: Not yet tested
- Verified: Tested on physical hardware or FTL

## Pass/Fail Thresholds

| Metric | Threshold | Notes |
|--------|-----------|-------|
| Wi-Fi Direct group creation | Must succeed within 3 attempts | Retry logic built into `WifiDirectManager` |
| VPN tunnel establish | `VpnService.prepare()` must return without error | Requires user consent on first use |
| Foreground service uptime | Must survive 60s under Doze simulation | Uses `PARTIAL_WAKE_LOCK` |
| Peer discovery | At least 1 broadcast received in 10s | May vary by OEM Wi-Fi firmware |
| SOCKS5 proxy bind | Must bind to group owner address | `192.168.49.1` on standard Android |

## OEM-Specific Workarounds

### Samsung

Samsung devices running One UI may require additional handling:

1. **Wi-Fi Direct network name**: One UI may override the `DIRECT-` prefix with its own naming scheme. The app accepts any network name starting with `DIRECT-`.

2. **Battery optimization**: Samsung's Adaptive Battery may throttle background services more aggressively than stock Android. The foreground service notification and `PARTIAL_WAKE_LOCK` mitigate this.

3. **Wi-Fi scan throttling**: Samsung enforces stricter Wi-Fi scan limits starting with One UI 5. Peer discovery may be delayed. The app uses `WIFI_P2P_PEERS_CHANGED_ACTION` broadcasts rather than active scanning.

### Xiaomi / MIUI

1. **Autostart permission**: MIUI requires explicit autostart permission for foreground services to survive app kill. Users must enable this in Settings > Apps > Manage apps > FlossHotspot > Autostart.

2. **Battery saver**: MIUI's battery saver kills background services aggressively. Annotated with `@OemWorkaround("xiaomi", "MIUI battery saver kills services")` where applicable.

3. **Wi-Fi Direct availability**: Some budget Xiaomi devices report `FEATURE_WIFI_DIRECT` but fail to initialize `WifiP2pManager`. The app checks `manager == null` after `getSystemService()`.

### OnePlus / OxygenOS

1. **Background process limits**: OxygenOS limits background processes more than AOSP. The foreground service with `connectedDevice` type helps maintain priority.

2. **Deep Doze**: OnePlus implements a more aggressive Doze variant. The wake lock timeout of 4 hours (`WAKE_LOCK_TIMEOUT_MS`) accommodates this.

### Huawei / HarmonyOS

1. **GMS dependency**: FlossWare Hotspot does not depend on Google Play Services for core functionality. Wi-Fi Direct and VPN services are part of AOSP.

2. **App launch restrictions**: Huawei's app launch manager may prevent the service from starting. Users should add the app to the "protected apps" list.

## Running Device Tests

### Locally via Firebase Test Lab

Prerequisites:
- [Google Cloud SDK](https://cloud.google.com/sdk/install) installed
- Firebase project with Test Lab enabled
- Service account key or `gcloud auth login` completed

```bash
# Build APKs
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest

# Run on a specific device
./ci/device-tests.sh redfin 30

# Run on a specific device with a custom project
FIREBASE_PROJECT=my-project ./ci/device-tests.sh oriole 33

# Run on Samsung Galaxy S23
./ci/device-tests.sh dm1q 34
```

Results are saved to `build/ftl-results/summary.json`.

### Via GitHub Actions

1. Navigate to **Actions** > **Device Compatibility Tests**
2. Click **Run workflow**
3. Optionally specify devices (e.g., `redfin:30,oriole:33`) or leave empty for the full matrix
4. Results appear as artifacts on the workflow run

### Required Secrets

The GitHub Actions workflow requires the following repository secret:

| Secret | Description |
|--------|-------------|
| `FIREBASE_SERVICE_ACCOUNT` | GCP service account key (JSON) with Firebase Test Lab permissions. Required roles: `cloudtestservice.testRunner`, `storage.objectAdmin` on the results bucket. See [Firebase Test Lab CI setup](https://firebase.google.com/docs/test-lab/android/continuous). |

To set up:
1. Create a service account in your GCP project
2. Grant roles: `Firebase Test Lab Admin`, `Cloud Storage Admin`
3. Export a JSON key
4. Add the JSON contents as a GitHub repository secret named `FIREBASE_SERVICE_ACCOUNT`

## Adding New Devices

1. Find the device model ID: `gcloud firebase test android models list`
2. Add a matrix entry to `.github/workflows/device-tests.yml`
3. Run the workflow to collect results
4. Update this table with findings
5. Add any OEM-specific workarounds to the codebase with `@OemWorkaround`
