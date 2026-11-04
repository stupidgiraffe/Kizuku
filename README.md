# Kizuku

An offline Android privilege platform with its own identity, a Shizuku compatibility
surface and independently scoped native capabilities. Android 8+; targeting Android 17.

**Signed developer preview with Wireless ADB pairing, custom TCP activation and per-app permission toggles.**

[Download Kizuku 0.1.0 Preview 2](https://github.com/stupidgiraffe/Kizuku/releases/download/v0.1.0-preview.2/Kizuku-v0.1.0-preview.2.apk) · [Release notes](https://github.com/stupidgiraffe/Kizuku/releases/tag/v0.1.0-preview.2) · [Passed signed acceptance](https://github.com/stupidgiraffe/Kizuku/actions/runs/41845954990)

The same signed APK passed the Android 17 emulator suite as shell UID 2000 and
root UID 0, including notification pairing, saved-key reconnect, custom TCP startup,
independent grants, revocation, persistence and lifecycle.
Physical-device/OEM and normal installer UI acceptance remain pending.

The Compose manager follows the Kizuku Command Center direction: navy/cobalt,
cyan/violet accents, expressive surfaces, real service state and compact controls.

## Build

JDK 21, Android SDK 37.0 and Build Tools 37.0.0:

```sh
./gradlew :core:test :manager:assembleDebug :test-client:assembleDebug :manager:lintDebug :test-client:lintDebug :compat:lintDebug
```

Manager APK: `manager/build/outputs/apk/debug/manager-debug.apk`.
Every main/PR build publishes debug APKs and verification reports in Actions.
No scheduled workflows. Debug signing is temporary; see [SECURITY.md](SECURITY.md).

## Current vertical path

- A permanent manager identity: `io.github.stupidgiraffe.kizuku`.
- One controller and `app_process` host for root, Wireless ADB, local TCP and computer ADB startup.
- Native protocol negotiation, certificate/UID-bound grants, one-time/session/
  permanent lifetimes, revocation, safe mode and Binder death handling.
- Stock v13 SDK attachment, permission checks, property reads and a bounded,
  system-target-validated Binder forwarding subset. User services, rish and process
  execution explicitly report unsupported; this is not full Shizuku compatibility.
- A bundled `moe.shizuku.privileged.api` companion with same-signer checks and
  PackageInstaller installation / Android uninstall confirmation.
- A separate-UID probe APK and opt-in emulator IPC acceptance workflow.

Install the manager normally. Open **Core → Wireless ADB / TCP** to pair, start
a previously paired device or enter an existing local TCP port. The Core tab
also has root and computer ADB startup. Settings → Compatibility installs the bundled legacy APK;
stock Shizuku must be removed by the user first if it occupies that package ID.
No permission decisions are silently migrated.

Full Shizuku user services, Axeron, modules, Device Owner and Labs remain open.

Read [status](docs/STATUS.md), [architecture](docs/ARCHITECTURE.md),
[source matrix](docs/UPSTREAMS.md) and [NOTICE](NOTICE) for evidence and lineage.
