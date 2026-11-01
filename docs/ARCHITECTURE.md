# Foundation contract

Kizuku is `io.github.stupidgiraffe.kizuku`. The legacy companion alone owns
`moe.shizuku.privileged.api`. This is an independent implementation with the stock
Shizuku AIDL contract, not a wholesale fork or branding replacement.

* `core`: Android-free capability grants, certificate/UID identity, recovery policy.
* `api`: native versioned Binder contract and signing identity helpers.
* `server`: `app_process` entry point, identity checks, native/legacy endpoints
  and unmodified upstream legacy AIDL. Native SDK consumers never inherit a second
  copy of the stock SDK classes.
* `manager`: Compose UI and the single activation/lifecycle controller.
* `compat`: bundled legacy permission/discovery companion, signed with the manager.
* `test-client`: independent UID using the published Shizuku SDK and native API.

Root and PC ADB launch the same entry point. Wireless ADB must launch that same
entry point after pairing; it must not invent another permission model. Child
runtimes may later be isolated, but grants remain central.

## Invariants

1. Binder caller UID comes from Android, never from a caller's Bundle. A claimed
   package must belong to that UID. Authorization includes current signing
   certificates and every shared-UID member. Uninstall/reinstall, certificate
   replacement or shared-UID membership changes invalidate grants.
2. Legacy Binder, native diagnostics and future shell/runtime scopes are separate.
   No grant means no operation. Unknown capabilities/protocol versions fail closed.
3. Manager operations require its UID **and** the signing identity captured from
   the installed, read-only APK used to launch the server.
4. Status is derived from a live Binder response. Death clears the connection.
   A stale heartbeat alone is not proof of death. Intentional stop and unavailable
   transport never trigger restart. Recovery is bounded and backs off.
5. Compatibility is reported by demonstrated operation. Unsupported user services,
   shell/runtime operations and extensions must be explicitly unavailable.
6. No cloud authorization, telemetry, login or phone modification during builds.

## First implementation sequence

- [x] Pin upstream evidence and licenses; initialize SDK 37 / min SDK 26 project.
- [x] Test capability isolation, signer replacement, shared UID changes, revocation,
      grant lifetimes and bounded recovery before implementing them.
- [x] Build the native Compose manager and authenticated `app_process` service.
- [x] Add stock SDK attach/permission/Binder adapter and bundled companion.
- [x] Compile, test, lint, upload APKs in GitHub Actions; exercise real IPC on emulator.

The full product brief is the phase authority. This foundation is not an alpha
claim. Wireless pairing, full user services, migration, signing-lineage rotation,
runtime plugins and device/OEM acceptance remain separate measured milestones.
