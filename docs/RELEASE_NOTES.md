Kizuku 0.2.0 Preview 1 adds stock Shizuku process streams, UserService, and rish to the same privilege host used for Axeron command execution.

- **Shizuku compatibility** covers Binder discovery and forwarding, UserService, process streams, and the stock rish launcher. Install or remove the companion through **Settings → Compatibility**. Android asks for confirmation. The companion package is also used by stock Shizuku and some forks.
- **Axeron runtime** provides independently authorized command execution, saved commands, scripts, and ZIP modules with actions, service scripts, and local WebUI support. Module state persists separately for shell and root. The manager also includes primary-user app enable/disable and a system settings editor.
- **Application manager** adds app search, filtering, and individual Shizuku and Axeron grants. Confirmed bulk Shizuku operations identify the affected apps and report errors per app.
- **Labs** includes Service Doctor, accessibility inventory, and optional device authentication. The main app and companion have distinct adaptive icons.
- **Home** shows Shizuku, Axeron, companion, and privilege status together. Start with root, Wireless ADB pairing or a saved key, custom local TCP, or computer ADB.

The signed release workflow verifies an in-place upgrade from 0.1.0 Preview 2 and exercises the resulting APK on Android 16 and 17 emulators as shell and root. APKs retain the permanent Kizuku signing certificate.

Axeron interactive PTY and the native socket and file API, the complete plugin WebUI API, BusyBox-specific installers, the SU bridge, and Device Owner/Dhizuku are still outstanding. Physical devices, OEM root-manager prompts, and broader third-party app compatibility remain unverified.
