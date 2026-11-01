# Implementation state

Updated 2026-11-02. Canonical work item: Plane Kizuku mainline #3,
`68ff91b5-1e6c-4a37-b755-8d284b4fffb4`.

## Implemented; verification in progress

- Six Gradle modules: core, API, server, manager, compatibility companion, probe.
- Native Compose command center, root/PC ADB activation, real Binder status/death.
- Native negotiation and diagnostics; separate legacy SDK attachment/permission
  and system Binder forwarding subset. Explicitly unsupported user services/rish.
- Certificate-bound capability ledger, shared UID awareness, one-time/session/
  permanent grants, manager-private persistence, revocation and safe mode.
- Bundled same-signer companion and PackageInstaller / uninstall UI.
- GitHub Actions compile/test/lint/APK workflow and optional API 37 IPC smoke lane.

## Evidence so far

- 16 Android-free policy/identity tests passed locally using JDK 21 / JUnit 4.13.2.
- Tests were first compiled before implementation and failed on missing policy types.
- Source metadata/revisions and latest releases verified for ten named upstreams.
- Supplied APK assets inspected locally; Render warmed and upload succeeded,
  but decoding timed out. No decompiled source was used.
- No physical device was installed or modified. Permanent signing identity is now
  established; see SECURITY.md. Encrypted GitHub secrets and independent encrypted
  recovery backup are saved, with recovery/password records in APIVault.
- GitHub Actions build job passed on commit `f9d37b0ac2ae91b5dcd5d5eaec1ae383b20e44ed`:
  full Android compilation, 14 policy/identity tests, lint (zero errors), all three
  debug APKs and both instrumentation APKs. [Run](https://github.com/stupidgiraffe/Kizuku/actions/runs/41291294717).
- [First APK bundle](https://github.com/stupidgiraffe/Kizuku/actions/runs/41291294717/artifacts/13077137842)
  (artifact ID 13077137842). APK package/signature and embedded companion were
  independently verified locally with aapt2/apksigner/ZIP inspection.
- First Android 17 emulator run booted, but its default userdata partition was
  too small to install even the companion. A second run exposed the emulator
  action treating sys.boot_completed as full framework readiness: input/settings
  were absent and the action exited before the test script. Both failures happened
  before app runtime acceptance. The workflow now starts the official emulator
  directly with a 6 GB partition and waits for stable package/activity/input/settings
  services, preserving boot logs. Captured native tombstones identified the precise
  `hasReadColorBufferDma` assertion. AVD metadata was incorrectly API 0: the runner's
  old command-line tools misparse `37.0`. Tools 22.0 (build 15859902) are now pinned,
  and the script rejects any AVD whose target is not `android-37.0` before boot.
  [Upstream reproduction](https://github.com/actions/runner-images/issues/14484).
  The corrected AVD booted, installed all five APKs and launched the Compose UI.
  Real IPC acceptance caught a missing Refine bytecode transform: ActivityManagerHidden
  is a compile-time shim, not an Android runtime class. Refine 4.4.0 is now applied;
  a DEX guard failed on the old APK and passed on the corrected local build.
  [Run 41295156000](https://github.com/stupidgiraffe/Kizuku/actions/runs/41295156000)
  then started the UID 2000 host and passed the real manager handshake and stale-
  approval rejection. Separate-client discovery exposed the manager-restart window;
  the native API and probe now await discovery with a bounded, cancellable worker-
  thread connection helper. Run 41295842746 then passed separate-UID native denial and actual stock SDK
  system Binder forwarding, but lost manager death notifications before native
  grants. AOSP JNI keeps death recipients weak on modern targets; the entire
  connection and manager lifetime token must be strongly rooted. The fix retains
  them for the host lifetime. Run 41321828416 passed native/legacy isolation,
  revocation, permanent grants after host restart and Binder death. Its remaining
  failure was a malformed content-CLI test argument (`s:grants:[]` instead of
  `grants:s:[]`), now corrected. The signed release workflow tests the actual
  signed artifacts as shell and root before allowing publication.
- Codemagic application `6ae6fc2bd51c43c2576e6be4` exists for the public repository
  using a generic Git connection. Manual debug YAML is committed; no build started.

## Review corrections

- Removed all exported provider grant writes. Approval persistence is manager-only;
  root/shell hosts can read and prune obsolete entries, never introduce grants.
- Approval sends a fingerprint of the full reviewed identity; both manager and
  server reject identity changes before live authorization.
- Identity includes each shared-UID member's first-install epoch. Reinstall/UID
  reuse fails closed; observed changed identities are invalidated, not suspended.
- Stock discovery uses process/UID observers. The acceptance client no longer uses
  a Kizuku-specific call to obtain its legacy Binder.
- Approval metadata is checked against the exact package, certificate and shared
  UID member set shown on-screen (16 local policy/identity tests).
- Root recovery has a cancellable bounded loop. Denial clears pending requests.
- Death is checked as connected → shutdown → dead in one manager instrumentation.

## Next acceptance gates

1. DONE: Android build/lint and first CI APK.
2. Exercise separate-UID native/stock SDK connection, independent grants,
   revocation, persisted trust after host restart and manager Binder death on emulator.
3. Review foundational security/IPC code and regress any discovered bugs.
4. Implement Wireless ADB pairing with SDK 37 local-network permission flow using
   a maintained upstream transport; same host entry point and authorization store.
5. Complete legacy automatic discovery, user services and full compatibility tests,
   companion migration and Android 16/17/OEM acceptance before calling it an alpha.
6. Configure permanent Kizuku signing in APIVault and Codemagic; only then release.

Keep Axeron, DexorX and ShizuPosed out of this first compatibility acceptance gate.
