# Implementation state

Updated 2026-11-17 JST. Canonical Plane mainline #3:
`68ff91b5-1e6c-4a37-b755-8d284b4fffb4`.

## Published 0.2.0 Preview 4

[Download the signed APK](https://github.com/stupidgiraffe/Kizuku/releases/download/v0.2.0-preview.4/Kizuku-v0.2.0-preview.4.apk) · [Release notes](https://github.com/stupidgiraffe/Kizuku/releases/tag/v0.2.0-preview.4) · [Signed Android 16 and 17 acceptance](https://github.com/stupidgiraffe/Kizuku/actions/runs/44005813896)

- Source `3b47f8995e9847bf1e7510f9ffd87166018c7fe4`, version code 7. The public 18,277,225 byte APK matches the accepted build and SHA-256 `303bde32afa6e03ad9ea5154dddaa6f70c97f8a7ee7cb70474ddb694fbff2d2a`.
- All four signed runtime and device-policy jobs passed. Updating from Preview 3 preserved the app UID, ADB identity and remembered shell grants under the permanent signing certificate.
- All ON, All OFF and individual app switches apply changes immediately. Each app’s access menu offers grant duration, and root and ADB-shell approvals remain separate.
- The Shizuku companion has no launcher activity. Existing companion installs can use Settings → Compatibility companion → Update. The installed package remains the Shizuku discovery identity.
- Settings includes GitHub, Buy Me a Coffee, manual checks, optional daily checks and automatic APK downloads. Emulator coverage exercised release selection, scheduling and APK identity checks. Android asks for approval to install. The full in-app download-and-install flow awaits a newer release.
- Automatic pairing reads the current address and code from the same Android Settings dialog, avoiding stale discovery endpoints. Manual notification pairing passed on both emulators.

## Published 0.2.0 Preview 3

[Download the signed APK](https://github.com/stupidgiraffe/Kizuku/releases/download/v0.2.0-preview.3/Kizuku-v0.2.0-preview.3.apk) · [Release notes](https://github.com/stupidgiraffe/Kizuku/releases/tag/v0.2.0-preview.3) · [Signed Android 16 and 17 acceptance](https://github.com/stupidgiraffe/Kizuku/actions/runs/43984722474) · [Feature matrix](UPSTREAMS.md#selective-integration--november-17-2026)

- Source `1b541c82bec469aaf015f98a6952efee6b702d2e`, version code 6. The published 18,261,989 byte APK matches the tested artifact and its SHA-256 `b634a1099a4cb6c736f5436b82662f5422f174b0bffc80f2e84eb31c32fbee0d`.
- Signed in-place upgrades from Preview 2 retained the app UID, ADB identity and stored shell grants. Root grants require fresh approval. The existing signing certificate and Shizuku compatibility companion passed verification.
- Both runtime jobs passed temporary automatic pairing, manual notification pairing, Hail Shizuku calls, stock rish, the UID-checked custom SU path in ADB-shell and root modes, and Axeron PTY input, resize, output and exit status. Tests used the original AxManager and Shevery WebUIs.
- Both device-policy jobs passed Device Owner, managed-profile Profile Owner, Amarok package-access delegation through the separately managed Dhizuku companion, and profile cleanup. Dhizuku coverage is limited to Android package access. Raw Binder forwarding and Dhizuku UserService are unavailable.
- The upstream radar compares exact stable and prerelease tags and commits, keeps one update issue for relevant changes, and can retain opt-in reference APKs as short-lived test artifacts. [Main workflow verification](https://github.com/stupidgiraffe/Kizuku/actions/runs/43984681119)

## Published 0.2.0 Preview 2

[Download the signed APK](https://github.com/stupidgiraffe/Kizuku/releases/download/v0.2.0-preview.2/Kizuku-v0.2.0-preview.2.apk) ·
[Release notes](https://github.com/stupidgiraffe/Kizuku/releases/tag/v0.2.0-preview.2) ·
[Passed signed workflow](https://github.com/stupidgiraffe/Kizuku/actions/runs/43749949949)

- Source `5df4a477775f4c9f3232d217241e33662d3af526`, version code 5.
- APK size 17,594,874 bytes. SHA-256
  `14ab7db569f40f853d18fbd9274995ecdbf135aa99f7df6a8b52a83edff68295`. The public download and published checksum match
  the signed acceptance artifact.
- Android 16 and 17 emulators updated in place from Preview 1, preserving
  the app UID, remembered Shizuku and Axeron grants, wireless pairing preference,
  and ADB key. The APK verified against the permanent signing certificate.
- Both emulators passed fresh installs as shell UID 2000 and root UID 0.
  Tests covered All ON and All OFF with eligible and mixed counts, the companion
  lifecycle, wireless pairing and reconnect, local TCP and computer ADB,
  stock Shizuku IPC and rish, and separate Axeron authorization.
- Hail 1.11.0 exercised a real Shizuku force-stop. Sys Info 2.2 exercised a real
  Shevery WebUI and its command bridge. Additional tests covered action and service
  lifecycle, authenticated status and stop, rejected keys, accessibility toggles,
  the Labs authentication gate, and Device Owner enrollment, suspension,
  uninstall protection, policy reversal, and ownership removal.
  Runtime evidence artifacts are 14175987456 for API 36 and 14176845170 for API 37.

### Shevery 14.6.5 integration

The [official APK comparison](https://github.com/stupidgiraffe/Kizuku/actions/runs/43746124198)
verified the release archive and signer, captured stopped and running Home screens,
and started Shevery over computer ADB as shell UID 2000 on Android 16.
The [feature comparison](UPSTREAMS.md#shevery-1465-comparison) records the exact
tag and source, plus implemented, partial, missing, and unsuitable features.

Preview 2 adds visible bulk controls, local Shevery module compatibility,
authenticated lifecycle intents, app and companion authentication, accessibility
toggles, structured Service Doctor export, and opt-in Device Owner controls.
External Dhizuku, Profile Owner, network WebUI APIs, the full module catalog,
and native Tasker integration remain open. Automated startup requires a saved
root or paired Wireless ADB method. Emulator root uses `adb root`.
OEM root-manager prompts, physical-device behavior, and successful real
biometric unlocking still need device acceptance.

## Published 0.2.0 Preview 1

[Download the signed APK](https://github.com/stupidgiraffe/Kizuku/releases/download/v0.2.0-preview.1/Kizuku-v0.2.0-preview.1.apk) ·
[Release](https://github.com/stupidgiraffe/Kizuku/releases/tag/v0.2.0-preview.1) ·
[Passed signed workflow](https://github.com/stupidgiraffe/Kizuku/actions/runs/43200546463)

- Source: `6c8acc2eb5e87bd7a0b929c755230e16e90dbc1b`, version code 4.
- Signed APK: 17,512,658 bytes. SHA-256
  `47c49d93dc0ecef1b87e088182212ec8eb0039489b7fd5cc434aa739a829198c`.
  The public download matches both the published checksum and the signed
  acceptance artifact byte-for-byte.
- Verified permanent signatures, non-debuggable builds, pairing and rish
  libraries for four ABIs, and the embedded signed companion and notices.
- Android 16 (API 36) and 17 (API 37) Google APIs x86_64 emulators passed
  in-place update from the public Preview 2 APK, retaining the manager UID
  and remembered Shizuku grant. Both also passed fresh-install acceptance as
  shell UID 2000 and root UID 0. Evidence artifacts: 13935961443 and 13936200533.
- Acceptance covered companion system confirmation, cancel and removal, app
  grants and bulk revoke, TCP startup, notification pairing and saved-key
  reconnect, computer ADB startup, stock Shizuku SDK discovery and Binder
  transactions, process streams, UserService, the stock rish loader, independent
  Axeron authorization and revocation, plugin lifecycle and WebUI, Labs, and host
  teardown. Android 17 also verified denied local-network permission.
- Emulator root uses `adb root`. OEM root-manager prompts and physical devices
  still need testing.

The release integrates stock legacy process pipes, UserService
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
BusyBox-specific installers, broader third-party compatibility, SU bridge,
automation and Device Owner/Dhizuku are outstanding. Runtime status is measured
independently of legacy host availability. Physical device and OEM behavior
remain unverified.

The [November 10 Android CI run](https://github.com/stupidgiraffe/Kizuku/actions/runs/42773418551)
passed build and lint. Android 16 and 17 both booted and reached the companion
installer. On cancel, the manager discarded the recorded session when Android
removed it, before Android sent the final result. The test stopped on the
generic message in both jobs, before ADB and runtime acceptance. The follow-up
keeps the session ID until the final callback or explicit reset. Companion
failures still fail acceptance while allowing the remaining suite to run.

The [November 12 rerun](https://github.com/stupidgiraffe/Kizuku/actions/runs/43072452390)
passed companion install/cancel/removal, ADB activation, shell plugin lifecycle,
and Labs on both API 36 and 37. The stock process test timed out with stdin still
open because the host pump did not flush the Java process stream. Root runtime
acceptance reached the WebUI file transfer, where the regular-file Binder
transaction failed while the host remained alive. The follow-up flushes command
streams and serves WebUI assets through a pipe.

The branch adds a Settings export with the stock Shizuku v13.6.0 rish launcher
and DEX. Their SHA-256 values match the original release. The stock loader
end-to-end case is separate from the raw rish Binder tests.

The [command and WebUI fix run](https://github.com/stupidgiraffe/Kizuku/actions/runs/43074205462)
passed build and lint, the complete Android 17 shell/root matrix, and Android 16
root. Android 16 shell passed companion install/cancel/removal, app grants,
activation cancellation and TCP. It failed before entering the wireless pairing
code, during Android's network trust prompt. The pairing test now waits for the
enabled Settings option and handles that prompt only on its SystemUI screen.

The [next debug run](https://github.com/stupidgiraffe/Kizuku/actions/runs/43198811373)
passed pairing and reconnect on both APIs. Android 17's grant test checked the
confirmation before the target was visible. The first signed build also hit
missing Kotlin dependency metadata. The final candidate puts the targets higher
in the dialog, waits for them to appear, and refreshes signed build dependencies.

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

## Remaining acceptance and feature work

1. Physical Android 16/17 and OEM acceptance, including root-manager prompts,
   accessibility-assisted pairing, on-device install/update, and broader
   third-party Shizuku, Axeron and Dhizuku client testing.
2. Axeron PTY basics now pass emulator acceptance. Full native socket/file APIs,
   plugin installers, module background lifecycle and wider WebUI compatibility
   still require implementation and real-module tests.
3. The custom-path SU bridge is implemented and tested in shell/root modes;
   it does not provide universal root or claim success for unavailable operations.
   Dhizuku package-access delegation works in emulator policy tests; raw Binder
   forwarding, Dhizuku UserService, additional delegated scopes, and wider
   external-client compatibility remain incomplete.
4. Boot-time automation, native Tasker plugin integration, certificate-lineage
   rotation, physical biometric acceptance, and a full in-app updater
   download-to-install cycle against a newer signed APK remain open.
