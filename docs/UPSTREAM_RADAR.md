# Kizuku upstream radar

Last changed: 2026-11-24 02:29 UTC. Automated metadata check; **not** a source-code, security, or compatibility audit.

Source discovery is best-effort and not exhaustive. No upstream code, APKs, or dependencies are copied or upgraded.

## Recent signals

- **[rushiranpise/Shizuku-Next](https://github.com/rushiranpise/Shizuku-Next/releases/tag/v14.0.38-next)** — Release v14.0.36-next → v14.0.38-next
  - Wear OS watch layouts and pairing support.
  - More reliable ADB and wireless debugging: pairing below Android 10, port fallback and restore, and cancellation handling.
  - App hiding fixes, including permissions for renamed apps and Android 17 debug switches.
- **[thejaustin/ShizukuPlus](https://github.com/thejaustin/ShizukuPlus/releases/tag/13.7.0.r3445)** — Release 13.7.0.r3395 → 13.7.0.r3445
  - Nav bar - icon shortcut menu with grouped Customize, spring drag and settle, TalkBack move left/right, one save per customizer drag; fix Pill corner, reveal state and popup leak (b
  - Pairing code dialog stays open on a missing port or a code that isn't 6 digits and says which - a typo closed the dialog with nothing sent (e7e811d6)
  - Pairing screen shows what's in the way and the one thing to do next - Developer options, Wi-Fi, Wireless debugging, local network and notification problems used to surface only as 
- **[cr1437/Shizako](https://github.com/cr1437/Shizako)** — New HEAD docs: 刷新徽章计数
- **[iamr0s/Dhizuku](https://github.com/iamr0s/Dhizuku)** — New HEAD Bump the maven-dependencies group with 3 updates (#360)

## Feature opportunities to evaluate

- **ADB activation and service recovery** — [thejaustin/ShizukuPlus](https://github.com/thejaustin/ShizukuPlus/releases/tag/13.7.0.r3445): Nav bar - icon shortcut menu with grouped Customize, spring drag and settle, TalkBack move left/right, one save per customizer drag; fix Pil
- **Capability security and grants** — [rushiranpise/Shizuku-Next](https://github.com/rushiranpise/Shizuku-Next/releases/tag/v14.0.38-next): Wear OS watch layouts and pairing support.
- **Android 16/17 and stock Shizuku compatibility** — [cr1437/Shizako](https://github.com/cr1437/Shizako): docs: 刷新徽章计数
- **ADB activation and service recovery** — [iamr0s/Dhizuku](https://github.com/iamr0s/Dhizuku): Bump the maven-dependencies group with 3 updates (#360)

## Tracked projects

| Project | Role | Release | Last push | License |
|---|---|---|---|---|
| [AMINODa/aMiNo-App](https://github.com/AMINODa/aMiNo-App) | ADB workflows and client compatibility | v1.4.2 | 2026-11-10 | Apache-2.0 |
| [corvexis/Axora](https://github.com/corvexis/Axora) | Axeron family alternative | 26.10.21 | 2026-10-21 | Apache-2.0 |
| [cr1437/Shizako](https://github.com/cr1437/Shizako) | compatibility and manager UX | zako4 | 2026-11-24 | Apache-2.0 |
| [d4rken-org/porter](https://github.com/d4rken-org/porter) | compatibility, companion, authorization | v0.8.2-rc0 | 2026-11-13 | Apache-2.0 |
| [fahrez182/AxManager](https://github.com/fahrez182/AxManager) | Axeron, plugins, WebUI and security | v3.0.0 | 2026-11-01 | Apache-2.0 |
| [HmnDev-Tech/shevery](https://github.com/HmnDev-Tech/shevery) | UX, permissions, Dhizuku, modules | 14.6.5 | 2026-11-18 | Apache-2.0 |
| [HSSkyBoy/Shizuku](https://github.com/HSSkyBoy/Shizuku) | device and wireless fixes | v13.6.2 | 2026-11-08 | Apache-2.0 |
| [iamr0s/Dhizuku](https://github.com/iamr0s/Dhizuku) | Device Owner protocol reference | v2.13.0 | 2026-11-23 | GPL-3.0 |
| [kerneldroid/Nightzuku](https://github.com/kerneldroid/Nightzuku) | Android platform fixes; paused project | v13.7.1-r63 | 2026-11-22 | Apache-2.0 |
| [krishna3163/OmniShizuku](https://github.com/krishna3163/OmniShizuku) | app management and utilities | latest | 2026-11-21 | Apache-2.0 |
| [qianyumeng0228/ShizukuX](https://github.com/qianyumeng0228/ShizukuX) | compatibility features | v13.7.0.k2018 | 2026-11-22 | Apache-2.0 |
| [revBlackCore/RevCoreManager](https://github.com/revBlackCore/RevCoreManager) | Axora-derived plugin runtime compatibility and UI reference | v26.10.21.r1 | 2026-11-17 | Apache-2.0 |
| [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) | baseline protocol | v13.6.0 | 2025-06-18 | Apache-2.0 |
| [roro2239/Stellar](https://github.com/roro2239/Stellar) | boot recovery and script execution | v1.1(101000) | 2026-10-18 | MPL-2.0 |
| [rushiranpise/Shizuku-Next](https://github.com/rushiranpise/Shizuku-Next) | ADB and diagnostics | v14.0.38-next | 2026-11-23 | Apache-2.0 |
| [Sharif-bot-cmd/ShizuPosed](https://github.com/Sharif-bot-cmd/ShizuPosed) | optional Labs inspiration | 1.5.1 | 2026-11-22 | Apache-2.0 |
| [thedjchi/Shizuku](https://github.com/thedjchi/Shizuku) | legacy startup and automation | v13.7.0-thedjchi | 2026-07-15 | Apache-2.0 |
| [thejaustin/ShizukuPlus](https://github.com/thejaustin/ShizukuPlus) | extended compatibility and SU bridge | 13.7.0.r3445 | 2026-11-24 | Apache-2.0 |
| [xeonleonreal/Xhizuku](https://github.com/xeonleonreal/Xhizuku) | module lifecycle and diagnostics | v4.0.0 | 2026-11-21 | Apache-2.0 |
| [Youngupdatesource/fOxygen](https://github.com/Youngupdatesource/fOxygen) | AxManager-derived terminal PTY, axsh, WebUI, plugin lifecycle, low-power recovery and UI performance | v1.4 | 2026-11-20 | Apache-2.0 |
| [asrtroh-netizen/shizuku](https://github.com/asrtroh-netizen/shizuku) | discovered — review before adoption | V15.5.0 | 2026-09-06 | Apache-2.0 |
| [Batestinha/shizuku-thedjchi-daemonstatebroadcast](https://github.com/Batestinha/shizuku-thedjchi-daemonstatebroadcast) | discovered — review before adoption | v13.7.0.r1365-thedjchi | 2026-09-10 | Apache-2.0 |
| [bugragungoz/shizuqu](https://github.com/bugragungoz/shizuqu) | discovered — review before adoption | v13.7.0 | 2026-10-30 | Apache-2.0 |
| [ivankwanpn/slate-fork](https://github.com/ivankwanpn/slate-fork) | discovered — review before adoption | slate-v0.3.7 | 2026-11-12 | Apache-2.0 |
| [Kohlrabenschwarz/Shizuku](https://github.com/Kohlrabenschwarz/Shizuku) | discovered — review before adoption | v13.6.1 | 2026-09-19 | Apache-2.0 |
| [niait/Cinnazuku](https://github.com/niait/Cinnazuku) | discovered — review before adoption | Beta | 2026-09-05 | Apache-2.0 |
| [seandyadryan/GPS-changer-Shizuku-Pro](https://github.com/seandyadryan/GPS-changer-Shizuku-Pro) | discovered — review before adoption | v14.0.32.r1995-pro | 2026-11-05 | Apache-2.0 |
| [tanmaysoren/SU-Manager](https://github.com/tanmaysoren/SU-Manager) | discovered — review before adoption | v1.0 | 2026-10-31 | NOASSERTION |
| [Terminator7245/Kitsune-Shizuku](https://github.com/Terminator7245/Kitsune-Shizuku) | discovered — review before adoption | — | 2026-08-26 | UNKNOWN |
| [yagribochek/shevery-info-via-prop](https://github.com/yagribochek/shevery-info-via-prop) | discovered — review before adoption | — | 2026-11-15 | MIT |
| [zirvepaul/AX-Manager-Forked-Shizuku-Auto-Starter](https://github.com/zirvepaul/AX-Manager-Forked-Shizuku-Auto-Starter) | discovered — review before adoption | v1.0.0 | 2026-09-08 | UNKNOWN |
| [Zoefiro/Shizuku-Stable-Debugg-Fix](https://github.com/Zoefiro/Shizuku-Stable-Debugg-Fix) | discovered — review before adoption | 13.7.0_DebuggFix | 2026-10-10 | Apache-2.0 |

## Other reference sources

- https://shizustore.com/apps/xhizuku
- https://shizustore.com/apps/adb-captain
- https://shizustore.com/apps/shizuku-next
- https://f-droid.org/en/packages/adb.captain/
- https://shizukux.xyz/

## Decision rule

Treat a release note as a lead, not proof. Review actual diffs and licenses, assess Kizuku's gaps, then implement behind its existing single-host, caller-identity, capability-grant and compatibility contracts. Prioritize security/Android regressions, Shizuku compatibility, Axeron parity, and user-visible reliability before cosmetic changes.
