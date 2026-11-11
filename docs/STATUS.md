# Implementation state

Updated 2026-11-12 JST. Canonical Plane mainline #3:
`68ff91b5-1e6c-4a37-b755-8d284b4fffb4`.

## November 10 integration branch

`integration/kizuku-major` integrates stock legacy process pipes, UserService
and rish server support, an independently authorized Axeron 3.0 command endpoint,
and a manager runtime for saved commands, plugin ZIP lifecycle, local WebUI,
primary-user app enable/disable and settings editing. It also adds app filters and
bulk Shizuku grants, a Service Doctor, accessibility inventory, opt-in device
authentication, companion installer results, adaptive layouts and both supplied
launcher icons.

The runtime uses one privileged host with separate shell and root directories.
A remembered Shizuku or Axeron grant follows the active host privilege. Consent
discloses that a later root start confers root execution for the authorized wire.
Axeron's interactive PTY and native socket/file service, complete plugin WebUI API,
BusyBox-specific installers, full third-party compatibility, SU bridge,
automation and Device Owner/Dhizuku are outstanding. Android 16/17 acceptance,
physical/OEM acceptance, update from Preview 2, and signed publication are gated.
Runtime status is measured independently of legacy host availability.

The [November 10 Android CI run](https://github.com/stupidgiraffe/Kizuku/actions/runs/42773418551)
passed build and lint. Android 16 and 17 both booted and reached the companion
installer. On cancel, the manager discarded the recorded session when Android
removed it, before Android sent the final result. The test stopped on the
generic message in both jobs, before ADB and runtime acceptance. The follow-up
keeps the session ID until the final callback or explicit reset. Companion
failures still fail acceptance while allowing the remaining suite to run.

The branch adds a Settings export with the stock Shizuku v13.6.0 rish launcher
and DEX. Their SHA-256 values match the original release. The stock loader
end-to-end case is separate from the raw rish Binder tests.

## Published Preview 2

[0.1.0 Preview 2](https://github.com/stupidgiraffe/Kizuku/releases/tag/v0.1.0-preview.2)
ships on-device notification pairing, saved-key Wireless ADB reconnect, classic
local TCP startup and installed-app permission switches.

- Source: `65d0b5180a5382357c3f8d447a7632dd6faa86f3`.
- [Signed acceptance and publication](https://github.com/stupidgiraffe/Kizuku/actions/runs/41845954990):
  build, acceptance and publication passed. The build ran 21 unit tests, lint,
  permanent-certificate checks and bundled companion/notice/native ABI checks.
- API 37 Google APIs x86_64 emulator: app grants/revocation, local-network denial,
  cancellation, custom TCP port 37055, notification code pairing, and reconnect
  with the persisted key. Full cross-UID Binder suites passed as shell and root.
- APK: `Kizuku-v0.1.0-preview.2.apk`, 16,352,194 bytes.
  SHA-256 `487c76dab988ce3f0cc5c3952b83207737a97849553befe7e158f1905772f11f`
  matches both the tested artifact and the GitHub release asset.
- Runtime fixes cover the classic ADB PTY hangup and the race between publishing
  the pairing reply action and making the controller available for pairing.
  No physical device was modified or tested.

## Previous published checkpoint

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

1. Full legacy user services/rish and migration; current forwarding is a subset.
2. Physical Android 16/17/OEM acceptance, including `su` activation and normal
   PackageInstaller/uninstall UI. Companion embedding/state checks are verified;
   automated direct installation is not installer-UI acceptance.
3. Signing-lineage rotation, richer local diagnostics/audit and watchdog acceptance.
4. Axeron runtime after the full Phase 1/1.5 acceptance gate; keep DexorX and
   ShizuPosed separate from the privileged core.

Visual follow-up: system status-bar icon contrast in a light OS theme needs
adjustment; in-app controls and service-state layout were readable in review.
Codemagic app `6ae6fc2bd51c43c2576e6be4` exists; the working signed lane is GitHub
Actions. Its manual release workflow requires runtime acceptance before publishing.
