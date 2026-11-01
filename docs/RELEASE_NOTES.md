Kizuku 0.1.0 Preview 2 adds on-device Wireless ADB pairing/start, manual local TCP/custom ports, and installed-app authorization switches. The pairing notification keeps Android Settings visible while entering the code. Previously paired devices reuse their encrypted local ADB identity.

Root, Wireless ADB, TCP and computer ADB launch the same privileged core. Existing Shizuku Binder access and native Kizuku capabilities have independent certificate-bound grants. Compatible installed apps appear before their first access request, with remembered grants available before core startup.

Release publication requires signed Android 17 emulator acceptance: notification pairing, persisted-key reconnection, custom-port TCP startup, local-network denial, cancellation, app switches and the full shell/root Binder and authorization suite. APKs retain Kizuku's permanent signing identity. No telemetry or cloud authorization.

This remains a developer preview. Legacy SDK attach/permission/validated system-Binder forwarding are implemented; user services and rish are not yet supported. Axeron persistent shell/plugins are not enabled. Physical Android/OEM acceptance remains outstanding; root emulator tests do not prove a physical root manager's su prompt.

Install the bundled legacy companion from Settings → Compatibility for existing Shizuku clients. Its package conflicts with stock Shizuku and forks using the same legacy identity.
