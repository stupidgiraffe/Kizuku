# Kizuku

An offline Android privilege platform with Shizuku compatibility and an
independently authorized Axeron runtime. Android 8+ with an Android 17 target.

[Download the signed 0.2.0 Preview 1 APK](https://github.com/stupidgiraffe/Kizuku/releases/download/v0.2.0-preview.1/Kizuku-v0.2.0-preview.1.apk) ·
[Verification and limitations](docs/STATUS.md)

## Build

JDK 21, Android SDK 37.0 and Build Tools 37.0.0:

```sh
./gradlew :core:test :manager:assembleDebug :test-client:assembleDebug :manager:lintDebug :test-client:lintDebug :compat:lintDebug
```

Manager APK: `manager/build/outputs/apk/debug/manager-debug.apk`.
Every main/PR build publishes debug APKs and verification reports in Actions.
No scheduled workflows. Debug signing is temporary; see [SECURITY.md](SECURITY.md).

## Features in this checkout

- A permanent manager identity: `io.github.stupidgiraffe.kizuku`.
- One controller and `app_process` host for root, Wireless ADB, local TCP and computer ADB startup.
- Native protocol negotiation, certificate/UID-bound grants, one-time/session/
  permanent lifetimes, revocation, safe mode and Binder death handling.
- Stock v13 SDK discovery, permission checks, system Binder forwarding,
  UserService, process streams, and rish export.
- A bundled `moe.shizuku.privileged.api` companion with same-signer checks and
  PackageInstaller installation / Android uninstall confirmation.
- Independently authorized Axeron commands, saved scripts, ZIP modules,
  local WebUIs, app enable/disable, and system settings editing.
- App search and filtering, remembered grants, and confirmed bulk Shizuku access changes.
- Labs with Service Doctor, accessibility inventory, and optional device authentication.

Open **Home → Wireless ADB / TCP** to pair, reconnect with a saved key, or enter a
local TCP port. Home also has root and computer ADB startup. **Settings → Compatibility**
installs or removes the bundled companion and exports the stock rish files.
If stock Shizuku occupies the companion package ID, remove it before installing
the companion and authorize your apps in Kizuku.

Read [status](docs/STATUS.md), [architecture](docs/ARCHITECTURE.md),
[source matrix](docs/UPSTREAMS.md) and [NOTICE](NOTICE) for evidence and lineage.
