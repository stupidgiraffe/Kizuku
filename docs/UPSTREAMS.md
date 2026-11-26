# Upstreams and compatibility decisions

## Selective integration · November 17, 2026

[Release pins and watched paths](../upstream-releases.json) record exact versions and peeled commits.
The attached APKs were checked against their manifests and GitHub asset SHA-256 digests.
AxManager 3.0.0.r509, all three Shizuku+ r3331 variants, Axora r377, and Shizuku-Next
14.0.36-next matched their published assets. Kizuku's baseline is signed Preview 2
(version code 5, source `5df4a477775f4c9f3232d217241e33662d3af526`).
The project-source list also includes OmniShizuku, Xhizuku, ADB Captain, stock
Shizuku, and aMiNo. Earlier reviews cover their relevant paths below.

| Decision | Feature | Evidence and boundaries |
| --- | --- | --- |
| Already implemented | Single host, separate Shizuku companion, manual pairing, saved-key reconnect, ADB and root, visible bulk grants | Signed Preview 2 acceptance covers both Android versions and host UIDs. Keep the existing on-device notification pairing and installable companion. |
| Already implemented | Axeron 3.0 Binder wire, command pipes, local ZIP plugins and WebUI | The AxManager and Axora APK providers use separate manager and client authorities. Preview 2 exercises independent Axeron authorization and runs an actual Shevery Sys Info WebUI. This evidence covers the existing local bridge. |
| Adopt | Temporary automatic wireless pairing | Next 14.0.36's `AdbPairingAccessibilityService` reads the Settings dialog and navigates known rows. Kizuku's candidate uses the AOSP `pairing_code` view ID and its existing local TLS discovery. The service is explicitly enabled through Settings for one requested attempt. |
| Adopt | Custom-path SU wrapper | Shizuku+ r3331 ships `assets/su`; it can delegate externally and reports a Magisk version. Kizuku's candidate reuses the existing rish transport, pairs a request with an actual host UID, and returns the command's exit status. |
| Improve | Remembered grants by host role | Preview 2 has one persisted grant list. The candidate keeps previous approvals and user data, assigning those unqualified approvals to ADB shell. Root requires explicit approval scoped to its own store. |
| Improve | Axeron interactive PTY and native client behavior | Axeron API 3.0 `ShellProcess` expects full argv, merged PTY output, window resize and a little-endian exit code. The candidate now shares the native host launcher with stock rish. The Android test exercises interactive input, resize, output and exit status over the Axeron wire. The pinned AxManager Test Function WebUI checks its original async JavaScript bridge. |
| Adopt | Restricted Dhizuku Device Owner/Profile Owner operations | The SDK 2.7 discovers the actual owner and calls its package-specific provider. Clients written against SDK 2.4 may require `com.rosan.dhizuku.server.provider` and the legacy package. Shizuku+ r3331 embeds a separate Dhizuku compatibility APK alongside its Shizuku companion. The candidate grants package-access delegation through Android DPM to an approved UID and certificate. A separate signed companion forwards verified legacy discovery. [Amarok 0.10.1](https://github.com/deltazefiro/Amarok-Hider/releases/tag/v0.10.1) exercises the stock SDK 2.4 path in CI. |
| Defer | Extra native socket, plugin catalog, firewall, background accessibility | Add native endpoints when a supported client demonstrates a requirement. Local plugin install and WebUI are the current tested path. |
| Reject | Arbitrary owner Binder forwarding, owner-UID UserService loading, donor manager/runtime imports, synthetic root results | Dhizuku API wraps a caller-supplied Binder and transaction. The inspected Shizuku+ relay rejects its own descriptors and forwards other targets. This exceeds the policy scope Kizuku intends to expose. |

[Shizuku-Next source](https://github.com/rushiranpise/Shizuku-Next/tree/e1837aee2a02cd4636446f4320bcc9472751f16f) ·
[Shizuku+ source](https://github.com/thejaustin/ShizukuPlus/tree/d8a162be561f78517ebfd5fb1d2d0a53604e4653) ·
[Dhizuku SDK](https://github.com/iamr0s/Dhizuku-API/tree/2a62cadcfa491c64003a6fb3f84b285a47ed0fca) ·
[Axeron SDK](https://github.com/fahrez182/Axeron-API/tree/bfceee9cecfb0451448b3453017b8326ee1f591e)

The [upstream radar](UPSTREAM_WATCH.md) compares stable and prerelease tags against the
reviewed pins and opens or updates one GitHub issue for relevant source changes.
On manual request it can retain changed APKs as three-day test artifacts.
Review the issue and advance the pins when those changes have been triaged.

## Initial upstream inspection · November 1, 2026

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

## Axora, AxManager and fOxygen comparison · November 24, 2026

Source review baseline: Kizuku `244c92855e770365d1731a6f701669cace776ae1`.
The gaps below describe that revision, before this integration candidate; they are
not runtime acceptance claims. GitHub release collections, tag refs and `main`
heads were checked on November 24. All three inspected application heads still
match their remote `main` branches.

| Source | Exact inspected revision | Latest actual release and license |
| --- | --- | --- |
| [Axora](https://github.com/corvexis/Axora/tree/c07aa503eb8bcfe32612b2320750a74f71819702) | `c07aa503eb8bcfe32612b2320750a74f71819702` | [26.10.21 / r380](https://github.com/corvexis/Axora/releases/tag/26.10.21), October 21, tag commit `9acb7f76816f6e8cbc6a651b17fe16b4fbf5b9bc`. Apache-2.0; LICENSE credits AxManager, copyright 2025 FahrezONE, and Shizuku/Rikka Apps. |
| [AxManager](https://github.com/fahrez182/AxManager/tree/7ed7f508464076d2617a9172c9de5bca2e7bcf7a) | `7ed7f508464076d2617a9172c9de5bca2e7bcf7a` | [v3.0.0](https://github.com/fahrez182/AxManager/releases/tag/v3.0.0), October 29, tag commit `1b08f1c9987ecf79b28c555fbcc7b2689147cf62`. Same Apache-2.0 application license and attribution. Current main includes changes after that release. |
| [fOxygen](https://github.com/Youngupdatesource/fOxygen/tree/243a9ed842afeb74a0a2180fc56df12c7ea246b4) | `243a9ed842afeb74a0a2180fc56df12c7ea246b4` | [v1.4](https://github.com/Youngupdatesource/fOxygen/releases/tag/v1.4), November 20, same commit. Same Apache-2.0 application license and attribution. |
| [Axeron-API](https://github.com/fahrez182/Axeron-API/tree/37eb6a3521828458394797e7166ff2e90946304e) | `37eb6a3521828458394797e7166ff2e90946304e`, AxManager main's `api` gitlink | MIT, copyright 2025–2026 FahrezRB. Published AxManager v3.0.0 instead pins `bfceee9cecfb0451448b3453017b8326ee1f591e`, already used by Kizuku. Preserve that release comparison when reviewing newer main. |

### Terminal behavior and reuse

Application manager paths below are relative to
`manager/src/main/java/frb/axeron/manager/` unless stated otherwise.
Axora's `ui/viewmodel/QuickShellViewModel.kt` uses an unlimited
event channel and a mutable Compose log. It offers command/input mode, saved
commands and ANSI color, but treats chunks as line updates. Current AxManager's
`ui/util/TerminalBuffer.kt` improves this with per-stream partial lines and split
escape handling, a 20,000-line ring or disk spool, serial background parsing and
32 ms snapshot publication. Its `ui/component/TerminalOutput.kt` reads visible
lines on demand and provides selection, search and scroll controls. fOxygen's
corresponding buffer drains in 8 ms main-thread slices instead. These are useful
transcript patterns; their parsers deliberately drop most cursor controls and
cannot provide a complete interactive terminal.

fOxygen's `ui/util/QuickShellSession.kt` starts each command in its own `setsid`
session, drains stdout/stderr concurrently, closes stdin on completion and handles
Binder death. Its EXIT marker and inherited-output detection distinguish
background work from a completed command; stopping targets the command's process
group. Reuse the ownership and cleanup principles through Kizuku's existing
process tracking, without adding another command engine. Its `RootlessShell.kt`
and `server/src/main/cpp/axdrop.cpp` lower root to UID 2000, groups and capabilities;
that does not establish equivalence to a real ADB launch or justify reusing shell
grants for root sessions.

Both newer donors use [AndroidANSI 1.2.1](https://github.com/Fox2Code/AndroidANSI/tree/f16c37b3f4440424a71193bc961c8caa7a2d3d06)
(`library` and `library-ktx`): MIT, copyright 2022 Fox2Code. The latest release
endpoint still reports May 3, 2023. It renders ANSI colors; it supplies neither
PTY transport nor VT cursor/screen emulation.

For a real terminal, prefer the pinned Java `terminal-emulator` and `terminal-view`
modules from [Termux `8629e632fcb95da272221be327db653fb24befe9`](https://github.com/termux/termux-app/tree/8629e632fcb95da272221be327db653fb24befe9).
Its root [LICENSE.md](https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/LICENSE.md)
explicitly places these two modules under Apache-2.0 as exceptions to the app's
GPLv3-only license. Preserve that exception and existing AOSP/Android Terminal
Emulator notices. The stock emulator artifact builds JNI, and `TerminalView`
depends on the final, JNI-backed `TerminalSession`; it cannot simply accept an
external PTY. A narrow vendored adaptation should retain the emulator/view Java
and selection resources, replace only `TerminalSession` transport with Kizuku's
Binder pipes, and exclude `JNI.java`, native subprocess code, `app` and
`termux-shared`. Feed raw UTF-8 bytes to `TerminalEmulator.append` on main through
a bounded queue, route input back to the pipe, and resize both emulator and
`IAxshProcess`. This retains mature IME, selection, cursor and screen behavior
while keeping one privileged host.

### Axeron interfaces and module expectations

| Boundary | Primary evidence | Kizuku baseline and decision |
| --- | --- | --- |
| Interactive process | API `aidl/src/main/aidl/frb/axeron/server/IAxeronService.aidl`, `IAxshProcess.aidl`; `axsh/src/main/java/frb/axeron/shell/host/ShellProcess.kt` | Existing Axeron PTY, full argv, resize and exit-status pipe are the foundation. Kizuku `RuntimeScreens.kt` still uses a completed-command Bundle with bounded output, so it does not expose that interactive path to the manager. Add a manager adapter to the existing host. |
| Native axsh | API `axsh/src/main/cpp/axsh_protocol.h`, `axsh/src/main/java/frb/axeron/shell/Sessions.kt`, `AxBroker.kt`; AxManager `server/src/main/java/frb/axeron/server/axsh/AxshServer.kt` | `openAxshChannel` throws and `getAxshSocketName` returns null. Null preserves ordinary Binder-backed broker fallback. Add native sessions only by dispatching to the existing authorized launcher; authenticate socket peers and recheck active role grants. |
| File service | API `api/src/main/java/frb/axeron/api/AxeronFileService.kt`, `server-shared/src/main/java/frb/axeron/server/api/FileServiceHolder.kt` | `getFileService` is unsupported. Implement the existing AIDL, checking UID, current signer and AXERON authority on every call. Read through caller-owned pipes because regular privileged-file FDs can fail SELinux Binder transfer. Signal write completion only after EOF, flush and fsync; clients wait for that callback before chmod/exec. |
| Packages and plugins | API `aidl/src/main/java/frb/axeron/server/PluginData.kt`, `server-shared/src/main/java/frb/axeron/server/Service.kt`; AxManager `data/PackageControl.kt` | `getPackages`, `getPlugins` and `getPluginById` are unsupported despite the local manager inventory. Adapt the same installed-package/module state, keeping Parcelables wire-compatible. PluginInfo JSON requires nested `prop`, plus `enabled`, `remove`, `action`, `web`, `size`, `dir_id` and update flags. |
| Module lifecycle | AxManager `manager/src/main/assets/scripts/functions.sh`, `reignite/src/main/java/frb/axeron/reignite/Igniter.kt`, `website/docs/plugin/environment.md` | Kizuku already supplies separate root/shell storage, ZIP install, action, service, uninstall, plugin PATH and process ownership. Its installer rejects `SKIPUNZIP=1`; BusyBox standalone ash, full installer helpers, donor staging/re-ignite and every boot convention remain distinct compatibility work. Keep explicit failure for unsupported installers. |
| WebUI | AxManager `ui/webui/interfaces/AxWebInterface.kt`, `KsuWebInterface.kt`, `website/docs/plugin/webui-api.md` | Kizuku supports local assets, consented command execution and async exec callbacks. Current main additionally exposes streamed `ksu.spawn`, package metadata/icons, file methods/output sessions, fullscreen/insets and shortcuts. Prioritize demonstrated module needs; preserve per-open consent and local origin/session checks when extending the bridge. |

The native AXSH v1 handshake is little-endian: `AXSH`, version byte `1`, flags
(PTY bit 0, login bit 1), argc and length-prefixed argv, envc and length-prefixed
environment; PTY adds 16-bit rows/columns. SCM_RIGHTS on the first byte carries
three non-PTY FDs (stdin read, stdout/stderr writes), or two PTY FDs (stdin read,
merged output write). Reply status is one byte; nonzero status adds a
length-prefixed error. Subsequent frames are client `0x03` with rows/columns and
server `0x02` with signed 32-bit exit status. Disconnect must destroy the session.
Only TERM, COLORTERM, LANG and LC_* client variables overlay the host environment
for a PTY. The Binder size packs rows, columns, x pixels and y pixels into four
16-bit fields; its separate exit pipe contains a little-endian int32.

`openAxshChannel` uses a socketpair: every byte carries a connected client socket
FD. Upstream verifies the forwarded socket's peer UID against the channel owner;
the root fast path uses a random abstract socket name each host start. Neither
transport may bypass Kizuku's installed-certificate checks, independent AXERON
grant or root/shell role. Do not import AxManager's Shizuku control/permission
stores, separate Reignite host, native BusyBox/resetprop binaries or artwork.
Any copied application code needs its Apache attribution; copied API protocol
code needs the separate MIT notice. This review performed no phone changes and
establishes source compatibility requirements, not device acceptance.

### Preview 5 integration

This integration preserves Preview 4's working activation, immediate bulk/single
Shizuku controls, hidden companion, scoped Dhizuku subset and SU wrapper.
The user confirmed all personally tested Preview 4 features worked. All four
signed Android 16/17 suites passed for released source `608c924`, including cached
file-handle reapproval and a live-host Preview 4 upgrade. The public APK matches
the accepted artifact. [Acceptance and precise tested operations](STATUS.md#published-020-preview-5)
record the original-client coverage and the single test-harness retry. These
specific client tests do not establish full AxManager compatibility.

| Area | Decision | Implemented behavior and boundary |
| --- | --- | --- |
| Interactive terminal | **Adopt / improve** | Termux's Apache emulator and view render the existing host PTY. Live input/output, ANSI cursor and colors, resize, control keys, termination, actual exits, searchable/copyable transcript and persistent snippets. No second terminal engine or daemon. |
| Native axsh | **Adopt** | AXSH v1, forwarded socket FDs, peer UID verification and role-specific AXERON grants dispatch to the existing process launcher. The unchanged AxManager DemoAxsh release is an acceptance fixture; arbitrary SDK/client combinations remain unproven. |
| File, package and plugin queries | **Adopt** | Original release wire layouts; caller UID/current signer on each file transaction, directional pipes, completion after flush/fsync, bounded pagination and accurate installed package/plugin state. Cached file handles reject revoked access and resume after explicit reapproval of the same identity. |
| Module lifecycle | **Improve** | Original installer variables and permission helpers, real current-UID `setprop`, separate shell/root homes, install/update/remove, action/service/post-fs logs and owned process-group cleanup. Unsupported installer conventions fail or remain documented; no bundled BusyBox/resetprop. |
| Local WebUI | **Improve** | Consent per open; streamed `ksu.spawn`, original local `kernelsu.js`, exec, package metadata, fullscreen/insets and shortcuts which request command access. Asset paths and session tokens are checked by the host. |
| Apps details | **Adopt selectively** | Secondary permissions/AppOps screen, force-stop and Android battery settings. Snapshot signer and host role are checked before changes. Bulk Shizuku controls stay immediate. |
| Update handling | **Improve** | Visible download progress; acceptance downloads the candidate through Preview 4, verifies its signer/hash and approves Android's installer before checking retained UID, grants and ADB identity. |
| Donor manager controls and every native API | **Defer** | Axeron grant mutation, manager initialization, embedded Shizuku controls, environment mutation, wake locks and user-service attach are explicitly unavailable. Use Kizuku's own separately authorized APIs. |
| Standalone axsh distribution | **Defer** | Kizuku serves the native AXSH wire for SDK clients with their own native binary. It does not bundle/export the donor's standalone `libaxsh.so` or its app-process bootstrap. External terminals can use the existing rish export. |
| Network WebUI, catalog and full donor installer environment | **Defer** | Local pages only. BusyBox standalone/`SKIPUNZIP`, all boot-stage semantics and unrestricted network APIs require demonstrated clients and separate acceptance. |
| Second host, shared root/shell grants, unrestricted relay | **Reject** | Preserve one privileged host and independent Kizuku authorization; Linux privilege failures remain failures. |

The temporary CI fixtures are the original AxManager v3.0.0
`DemoAxsh_v2.0.0_30000-release_2610291728.apk` (SHA-256
`17219acd16c8ba56ab7801a6c082c43a0678368ba94c0f2eebd0b01b01d0eecd`)
and `test_function-v2.0.0.zip` (SHA-256
`b5c6328f17800c91bce2440de5cd947f25142bbb9ec85264d310517613e10e9f`).
No competing manager is installed and neither binary is committed. Test Function
includes donor-specific diagnostics such as BusyBox, direct log FDs and exact
installer paths; a successful lifecycle test does not imply every diagnostic
matches AxManager. Record verified operations and remaining differences in STATUS.

Security review: manager calls require its installed signer and UID; client file
and native channel calls validate the current client identity and AXERON grant;
revocation closes streams/channels and stops owned jobs. Remembered shell/root
grants use different stores. ZIP path, symlink and expansion checks remain in the
existing installer. WebUI assets are local, subframes/network/file access are
blocked, commands require a current module token, and closing a page terminates
its streamed processes. Terminal OSC 52 clipboard writes are disabled. Physical
phone/OEM behavior and hostile-module containment are not established by emulator
tests: a user-approved shell-capable module has the host's actual authority.
