# Contributing to FlossWare Hotspot

Thank you for considering contributing to FlossWare Hotspot. This document explains how to set up the project, submit changes, and what to expect during the review process.

## Code of Conduct

This project follows the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md). By participating, you agree to uphold its standards.

## Getting Started

### Prerequisites

| Requirement | Version | Notes |
|-------------|---------|-------|
| JDK | 21 | Temurin recommended |
| Android SDK | API 35 | via Android Studio or `sdkmanager` |
| Android NDK | **27.0.12077973** | Exact version required for native build |
| Git | any | Submodule support required |

### Clone and Build

```bash
# Clone with submodules (required for the native C library)
git clone --recursive https://github.com/FlossWare/hotspot-android.git
cd hotspot-android

# Build both APKs
./gradlew assembleDebug

# Run unit tests
./gradlew test
```

If you cloned without `--recursive`, initialize the submodule before building:

```bash
git submodule update --init --recursive
```

For detailed build instructions, NDK setup, and troubleshooting, see [docs/BUILD.md](docs/BUILD.md).

## How to Contribute

### Reporting Bugs

Open an issue on [GitHub Issues](https://github.com/FlossWare/hotspot-android/issues) with:

- Device model and Android version
- Steps to reproduce
- Expected vs actual behavior
- Logcat output if applicable (`adb logcat -s HotspotService Socks5Server DnsRelay TunnelService`)

### Suggesting Features

Open an issue with the **enhancement** label. Describe the use case and why it would benefit the project.

### Submitting Code

1. **Fork** the repository
2. **Create a branch** from `main`:
   ```bash
   git checkout -b fix/description-of-change
   ```
3. **Make your changes** (see code style below)
4. **Run tests** and verify the build:
   ```bash
   ./gradlew test
   ./gradlew assembleDebug
   ```
5. **Commit** with a clear message:
   ```bash
   git commit -m "Fix DNS relay cache eviction when TTL expires"
   ```
6. **Push** to your fork and open a **Pull Request** against `main`

### Branch Naming

| Prefix | Purpose |
|--------|---------|
| `fix/` | Bug fixes |
| `feature/` | New features |
| `docs/` | Documentation changes |
| `refactor/` | Code restructuring without behavior changes |

### Commit Messages

- Use present tense ("Add feature" not "Added feature")
- Keep the first line under 72 characters
- Reference issues when applicable ("Fix DNS cache overflow (#42)")
- One logical change per commit

## Code Style

### Language

All application code is written in **Kotlin**. The native tunneling library (hev-socks5-tunnel) is C, but contributors do not modify it directly -- it is a git submodule.

### Formatting

- **Indentation:** 4 spaces (no tabs)
- **Line length:** 120 characters max
- **Trailing commas:** Used in multiline parameter lists and collection literals
- **Imports:** No wildcard imports

The project includes an [.editorconfig](.editorconfig) that most IDEs will respect automatically.

### Patterns

Follow the existing patterns in the codebase:

- **State management:** `StateFlow` with sealed class hierarchies (see `WifiDirectState`, `BluetoothState`, `VpnState`)
- **Services:** Android foreground services with action-based `onStartCommand` dispatching
- **Concurrency:** `ThreadPoolExecutor` with `CallerRunsPolicy` for backpressure in proxy code; Kotlin coroutines for UI and lifecycle
- **Error handling:** Fail gracefully, log with `android.util.Log` using class-name tags
- **Testing:** Pure-JVM unit tests (no Android framework dependencies in proxy/model code). Use `isReturnDefaultValues = true` in test config.

### Project Structure

The project has two modules:

| Module | Artifact | Purpose |
|--------|----------|---------|
| `app` | Host APK | Shares mobile data via Wi-Fi Direct + SOCKS5 |
| `client` | Client APK | Connects to host via VPN tunnel |

Code that touches the network proxy layer (`proxy/` package) should have no Android framework dependencies so it remains unit-testable on JVM.

## Issue Labels

| Label | Meaning |
|-------|---------|
| `bug` | Something is broken |
| `enhancement` | New feature or improvement |
| `documentation` | Documentation updates |
| `good first issue` | Suitable for new contributors |
| `help wanted` | Extra attention needed |
| `duplicate` | Already reported |
| `wontfix` | Will not be addressed |

## Pull Request Review

- All PRs require at least one review before merging
- CI must pass (build, tests, lint)
- Keep PRs focused -- one feature or fix per PR
- Update documentation if your change affects user-facing behavior
- Add tests for new functionality

## CI/CD

Every push to `main`:
1. Builds both debug and release APKs
2. Runs unit tests and lint
3. Auto-increments the version (X.Y format)
4. Creates a git tag

Pushing a `v*` tag triggers a release build with APKs attached to a GitHub Release.

CI does not run on documentation-only changes (`*.md`, `docs/**`, `LICENSE`).

## License

By contributing, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE).
