First signed developer preview of Kizuku's independent Android privilege platform.

- Native Compose manager: Core, Apps, Modules, Labs and Settings.
- Root and computer ADB startup of one privileged host.
- Separate certificate-bound Shizuku Binder and native diagnostic grants.
- Once, session and remembered authorization; revocation, safe mode and live Binder death handling.
- Bundled legacy Shizuku companion with Android installation/removal confirmation.
- Published only after signed APK tests exercise cross-UID authorization, revocation, persistence, host death, manager restarts and manager removal on Android 17 as both shell and root.

Install `Kizuku-v0.1.0-preview.1.apk`. The legacy companion is inside Settings → Compatibility. Stock Shizuku or another app occupying its package ID conflicts; Kizuku does not silently uninstall or migrate it.

**Scope:** a developer preview, not the first complete alpha. Wireless ADB pairing, Shizuku user services/rish, Axeron, modules and Device Owner are not implemented. Existing clients requiring user services or arbitrary process execution are unsupported. Physical phone/OEM acceptance has not been performed. Modules and Labs screens describe future features rather than presenting working controls.

This release establishes a permanent Kizuku signing identity for future updates. Earlier CI debug APKs use unrelated temporary debug keys; Android may require removing such a build before installing this signed preview, which deletes its local grants.

Offline core; no account or telemetry. Source lineage and licenses: see NOTICE and docs/UPSTREAMS.md in the repository. APK SHA-256 is attached alongside the APK; signing certificate SHA-256 is in SECURITY.md.
