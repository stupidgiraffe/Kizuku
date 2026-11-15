# Upstreams inspected — 2026-11-01

| Source | Inspected HEAD | Latest release | Use |
|---|---|---|---|
| [Nightzuku](https://github.com/kerneldroid/Nightzuku) | ca02387ec272 | v13.7.1-r58 | Dual interface, capabilities, Android 17 and watchdog reference |
| [Porter](https://github.com/d4rken-org/porter) | a288febb4e42 | v0.8.1-rc0 | Primary identity, companion and lifecycle reference |
| [Shevery](https://github.com/HmnDev-Tech/shevery) | 44cf522d8644 | 14.5.0 | Bundled Shizuku/Dhizuku companions, PackageInstaller, module policies |
| [Shizuku+](https://github.com/thejaustin/ShizukuPlus) | 171cf887e3ca | v13.6.0.r2551 | Companion and bridge lineage; later optional adapters |
| [ShizukuX](https://github.com/qianyumeng0228/ShizukuX) | 8a3ce4dd1fef | v13.7.0.r2002 | Same feature lineage; avoid duplicate engines |
| [AxManager](https://github.com/fahrez182/AxManager) | c6b44cc09481 | v3.0.0 | Phase 2 runtime, process ownership and certificate access |
| [Axora](https://github.com/corvexis/Axora) | c07aa503eb8b | 26.10.21 | Historical runtime comparison |
| [HSSkyBoy/Shizuku](https://github.com/HSSkyBoy/Shizuku) | 703e5ae68ccd | v13.6.1-r1 | Targeted wireless/OEM fixes |
| [ShizuPosed](https://github.com/Sharif-bot-cmd/ShizuPosed) | 7fdc7a8807ca | 1.3.2 | Optional later Labs bootstrap; absent from core |
| [Stock Shizuku](https://github.com/RikkaApps/Shizuku) | b844bc491f17 | v13.6.0 | Unique protocol origin; inactive since June 2025 |

All repository metadata reports Apache-2.0. This does not clear every bundled
native dependency or asset: review each component before importing it.

## Lineage decision

Build an independent small host using stock Shizuku AIDL, a separately identified
Compose manager, a same-signer legacy companion, and explicit native capabilities.
Porter supplies security/lifecycle patterns; Nightzuku supplies the dual-wire
reference. No complete fork is imported. The AxManager runtime remains Phase 2.

`server/src/main/aidl/moe/shizuku/server/` contains four unchanged stock Shizuku-API
AIDL files. `server/src/main/java/moe/shizuku/api/BinderContainer.java` is unchanged
stock source. The exact revision is recorded in `NOTICE` and `upstreams.lock.json`.
Gradle wrapper tooling was copied from the inspected Nightzuku checkout and pinned
to Gradle 9.6.0 independently. All other initial implementation is new Kizuku code.

## Binary evidence

- Uploaded Shizuku+ r2219 contains `assets/compat.apk`; upstream release is r2551.
- Uploaded Shevery is version 14.4.0 (current upstream release: 14.5.0) and contains `assets/shevery-stub.apk` and `assets/shevery-dhizuku-stub.apk`.
- Uploaded `shiz-release.apk` is `com.shizuposed.manager` version 1.0.5 (current release: 1.3.2) and contains ShizuPosed/Amiru/XStealth native libraries.
  It is a hooking reference, not assumed to be stock Shizuku.
- Uploaded Axora v1.5.1 r377 includes Axeron, BusyBox and resetprop. It predates
  current Axora 26.10.21 and AxManager 3.0.0.
- Render was warmed and the Shevery upload succeeded. Decoding exceeded the MCP
  timeout. The ZIP/asset observations above are verified directly from supplied files.

Additional maintained candidates surfaced through the current awesome-shizuku
catalogue: [Xhizuku](https://github.com/xeonleonreal/Xhizuku) and
[thedjchi/Shizuku](https://github.com/thedjchi/Shizuku). They are not source dependencies;
uninspected changes are not described as audited.

## Platform sources

- [Android 17 local network permission](https://developer.android.com/privacy-and-security/local-network-permission): SDK 37 discovery requires `ACCESS_LOCAL_NETWORK` or a system picker. The manifest declares it; pairing is not implemented yet.
- [AGP compatibility](https://developer.android.com/build/releases/about-agp): AGP 9.4.1 / Gradle 9.6.0 / JDK 21; SDK and target 37, minimum SDK 26.
- [Compose setup](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler): built-in Kotlin and matching Compose compiler 2.2.10; BOM 2026.08.00.
- Hidden package lookup uses long/int flag signatures and `getPackagesForUid`, avoiding changed installed-list return types. PermissionManager device-ID mutations are not implemented.

## ADB activation follow-up (2026-11-02)

Porter a288febb4e42 supplies the narrow key/pairing/native adapter; the host and
authorization database remain Kizuku's. Native framing is bounded before allocation,
startup output is limited, local endpoints are validated, and TLS pairing uses
Android's public exporter on API31+. See docs/ADB_PLATFORM.md for exact Android 17
permission and Settings lifecycle evidence. BoringSSL 20251124 and Bouncy Castle
1.86 were verified against their primary Maven/source metadata.

New uploaded `manager-release.apk` is Shevery 14.6.1 (com.hamondev.shevery), not
the older 14.4 reference. Manifest/resources inspected locally after Render's
resource-only decode timed out. The uploaded Shizuku 14.0.31-next digest matches
rushiranpise/Shizuku-Next tag v14.0.31-next, c97dc7d23c94cee90748128160c4a3366983aa12.
We reused its narrow queued-resolution/stale-port ideas, not its full engines; no
decompiled implementation was used.

## November 10 integration

| Reference | Source examined | Decision |
|---|---|---|
| [AxManager](https://github.com/fahrez182/AxManager/tree/v3.0.0) | 3.0.0 at `1b08f1c`; Axeron-API `bfceee9` | Implement the Axeron client wire and documented ZIP layout in Kizuku's host. Reuse the exact AIDL and wire payload layouts. Keep separately licensed native utilities outside the package. |
| [OmniShizuku](https://github.com/krishna3163/OmniShizuku) | `997cffa`, v1.0.0 and moving latest release | App freeze and accessibility inventory references. Use Android package state readback. Keep its debloat catalogue, firewall and bundled GPL tools outside this implementation. |
| [Xhizuku](https://github.com/xeonleonreal/Xhizuku) | `245d312`, v4.0.0 | Review module ZIP lifecycle and its test fixture. Preserve Kizuku's Binder-driven recovery instead of killing a host on a stale heartbeat. |
| [Shizuku-Next](https://github.com/rushiranpise/Shizuku-Next) | `9b8cb38` | Accessibility inventory and Settings handoff. |
| [Shizuku+](https://github.com/thejaustin/ShizukuPlus) | `8033070`, 13.7.0.r3216 prerelease | Service Doctor patterns. Preserve a single activation controller and bounded reconnect. |
| [Shevery](https://github.com/HmnDev-Tech/shevery) | `580a996`, 14.6.3 | Biometric protection and system installer reference. DPC/Dhizuku provider requires a separate protocol and authorization review. |
| [ADB Captain](https://f-droid.org/en/packages/adb.captain/) and [aMiNo](https://github.com/AMINODa/aMiNo-App/releases/tag/v1.4.2) | Published feature/reference artifacts | ADB workflows and external client candidates. No source or APK imports. |

OmniShizuku and Xhizuku top-level source licenses are Apache-2.0. OmniShizuku
contains components with separate GPL and MIT licenses. The attached APKs were
inspected for manifest identity and bundled companion/native files. The uploaded
Shevery is 14.6.1 and the uploaded Next is 14.0.31-next. Those binaries are
reference material and are not packaged in Kizuku.

## Shevery 14.6.5 comparison

[Official release](https://github.com/HmnDev-Tech/shevery/releases/tag/14.6.5) ·
[Pinned source](https://github.com/HmnDev-Tech/shevery/tree/f1f45543e16f98ae4c729721226893c32be130a5) ·
[Release changes](https://github.com/HmnDev-Tech/shevery/compare/14.6.4...14.6.5)

The uploaded APK matches the official asset SHA-256
`b793b2ecbdbed943c1df6819057d472c461b67ff94011c092b9c9c67b3626bb9`.
Its embedded assets include separate Shizuku and Dhizuku stubs, the rish launcher
and DEX, and native ADB, rish, and starter libraries for four ABIs. The reviewed
tag is Apache-2.0. Kizuku implements the selected behaviors through its existing
host. The Shevery APK and test modules are used on disposable emulators.

| Area | Shevery 14.6.5 source reviewed | Kizuku coverage |
| --- | --- | --- |
| App manager | `management/ApplicationManagementActivity.kt`, `authorization/AuthorizationManager.kt`; search, granted/denied, session access, manual inclusion and app details | **Implemented** for discovered Shizuku clients. Visible All ON/OFF with counts, mixed state, confirmation, progress and per-app failures. Uses installed manifest declarations and current signer/UID evidence. Runtime Android permission toggles, manual inclusion and cross-profile listing remain missing. |
| Startup and manager UI | `home/`, `adb/`, `native/starter/`; root, wireless and custom TCP, optional diagnostics, autoscale | **Implemented** root/shell startup, pairing, saved-key reconnect and manual TCP through one controller. Autoscale remains absent. |
| Compatibility stubs and IPC | `stub/src/shizuku/`, `stub/src/dhizuku/`, `dhizuku/DhizukuProvider.kt`, server client checks | **Partial.** Kizuku bundles its same-signed Shizuku companion and stock rish export. Axeron remains independently authorized. No Shevery SDK namespace or Dhizuku stub is installed. |
| Module ZIPs and local WebUI | `module/AdbModuleManager.kt`, `module/ModuleWebViewActivity.kt`, `docs/adb-modules-api.md`; nested packages, action and service paths, WebUI bridge and policy | **Partial.** ZIPs, local assets, actions, opt-in services and the local Shizuku JavaScript bridge run in the same host as Axeron modules. Privileged WebUI commands need per-open consent. Network, WebUI downloads and the catalog are outside this implementation. |
| Automation and recovery | `tasker/`, `service/WatchdogManager.kt`, `errorprotect/`, `native/starter/`; authenticated intents, boot and watchdog restart | **Partial.** Opt-in authenticated start, stop, restart, toggle and status, plus bounded Binder-driven recovery and saved transport. Tasker and MacroDroid can send explicit intents. Native plugin picker, boot startup and a separate supervisor remain absent. |
| Device protection and diagnostics | `security/AuthManager.kt`, `security/SecuritySettings.kt`, `home/SheverySmartLogsDialog.kt` | **Partial.** Optional system authentication for Apps decisions, companion changes and Labs. Service Doctor exports a structured status snapshot. Broad logcat capture and the donor's reusable biometric timeout session remain absent. |
| Accessibility and system tools | `accessibility/AccessibilityManager.kt`, `accessibility/AccessibilityDaemonService.kt`, `settings/NetworkFirewallManager.kt` | **Partial.** Confirmed primary-user accessibility toggles, app controls and system settings editing with readback. Persistent accessibility pinning and the firewall remain absent. |
| Device Owner | `deviceowner/DeviceOwnerManager.kt`, `settings/DeviceOwnerScreen.kt`, `docs/device-owner.md` | **Partial.** Explicit ADB enrollment and Android owner readback. Manager-only app suspension and uninstall protection, with reversal and role removal. Profile Owner, transfers, delegation and external Dhizuku remain missing. |
| Dhizuku binder relay and UserService | `dhizuku/DhizukuProvider.kt`, `DhizukuAuthManager.kt`, `DhizukuUserServiceHostService.kt` | **Unsuitable for direct port.** The reviewed relay dispatch accepts caller-supplied Binder transactions, the grant store relies on numeric UIDs, and donor UserService code runs with the owner app UID. External Dhizuku needs independently verified client identities and a constrained policy API. |
| Stealth and foreground hiding | `stealth/StealthApkGenerator.kt`, `settings/UsbDebuggingHideService.kt`, `settings/DeveloperOptionsHideService.kt` | **Unsuitable.** Package rewriting would disrupt Kizuku's update identity. Per-second foreground polling modifies global debugging state that Kizuku needs for ADB recovery. |
| Comput and native Rust rewrite | `comput/`, `commandium/`, `native/` | **Partial.** Kizuku has local commands and saved scripts. Cloud AI and a native toolchain replacement are outside this phase. The release fixes were reviewed for their IPC and lifecycle implications. |
