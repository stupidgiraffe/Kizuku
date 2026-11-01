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
