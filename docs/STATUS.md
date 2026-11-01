# Implementation state

Updated 2026-11-02 JST. Canonical Plane mainline #3:
`68ff91b5-1e6c-4a37-b755-8d284b4fffb4`.

## Current unreleased implementation

Source `a2edcc8df75afa29361f095ee81960d447d502f5` adds on-device notification
pairing, wireless/TCP startup, cancellation and compatible installed-app switches.
All 21 policy/protocol tests, debug compilation and lint passed on `0aac097`.
Its signed build also passed permanent-certificate, bundled-companion, notice and
four-ABI pairing-library checks.

Runtime gate 41338947735 did **not** exercise these features: changing the emulator
TCP port closed the original ADB transport; an unbounded cleanup command then hung
until job cancellation. `ba36cab` / `a2edcc8` bound ADB commands, reconnection probes
and diagnostic cleanup, and tolerate the expected transport disconnect. Corrected
signed/runtime gate: https://github.com/stupidgiraffe/Kizuku/actions/runs/41345959538
Do not call pairing, switches or TCP runtime-verified until that gate proves them.
Preview 2 remains unpublished. No physical phone was modified.

## Published checkpoint

[0.1.0 Preview 1](https://github.com/stupidgiraffe/Kizuku/releases/tag/v0.1.0-preview.1)
is published with a permanent signing identity. It is a developer preview of the
core, not the full alpha described in the product brief.

- Source: `9e77971dfad82820cc5594ea82bd6892ba687d92`.
- [Signed build and runtime acceptance](https://github.com/stupidgiraffe/Kizuku/actions/runs/41323692790): all three jobs passed, including publication.
- APK: `Kizuku-v0.1.0-preview.1.apk`, 13,141,845 bytes.
- APK SHA-256: `5c397a641224ad3ef29ca966be360bf225f78ed82de2920dffc4c26e73e0e26f`.
- Published download independently fetched and matched against that SHA-256.
- Signing certificate, recovery procedure and permanent-key guard: SECURITY.md.

## What was proved

- Release compilation, 16 policy/identity unit tests, release lint and DEX shim guard.
- Actual release APKs are non-debuggable and use the permanent certificate.
- Embedded legacy companion exactly matches its separately signed build;
  LICENSE and NOTICE are bundled and byte-checked.
- API 37 Google APIs x86_64 emulator, Pixel 7 profile: complete signed suite as
  shell UID 2000 and root UID 0. Root host was launched through `adb root`;
  this does not prove a physical device's `su` prompt or OEM behavior.
- Native negotiation and denial, stock SDK automatic discovery and a real system
  Binder transaction, independent grants, revocation, stale approval rejection,
  permanent grants across host restart, same-process death detection, denial of
  shell grant-store writes and host termination after manager removal.
- Both role suites retained 12 distinct instrumentation records (some stages run
  repeatedly), logs and screenshots in artifact 13090088748. Core stopped and
  connected states visually inspected on that emulator viewport.
- No physical phone was installed or modified. No telemetry/cloud backend added.

## Measured fixes leading to release

| Candidate | Result and correction |
|---|---|
| fc637dc | Manager restart/grants/revocation/persistence passed after rooting the Binder token and death recipient. Corrected a malformed content-CLI extra in the test script. |
| ec43e8c | Release D8 found duplicate legacy classes. Moved unchanged legacy AIDL into the server adapter; native API no longer exports them. |
| d406c3f | Full signed shell suite passed. Root host restart caught a manager-provider availability race during configuration loading. Added bounded read retries with no empty-trust fallback. |
| 9e77971 | Signed build, complete shell/root suites and publication passed. |

Earlier emulator infrastructure and missing Refine integration failures are
preserved in git history. No unchanged failure was blindly rerun.

## Next substantial gates

1. Wireless ADB pairing/start with SDK 37 local-network permission handling.
2. Full legacy user services/rish and migration; current forwarding is a subset.
3. Physical Android 16/17/OEM acceptance, including `su` activation and normal
   PackageInstaller/uninstall UI. Companion embedding/state checks are verified;
   automated direct installation is not installer-UI acceptance.
4. Signing-lineage rotation, richer local diagnostics/audit and watchdog acceptance.
5. Axeron runtime after the full Phase 1/1.5 acceptance gate; keep DexorX and
   ShizuPosed separate from the privileged core.

Visual follow-up: system status-bar icon contrast in a light OS theme needs
adjustment; in-app controls and service-state layout were readable in review.
Codemagic app `6ae6fc2bd51c43c2576e6be4` exists; the working signed lane is GitHub
Actions. Its manual release workflow requires runtime acceptance before publishing.
