# Kizuku 0.2.0 Preview 5

- Runtime has an interactive ANSI terminal with live input, resizing, control keys, search/copy, saved commands and real exit codes. It uses the existing host and PTY.
- Axeron adds native axsh channels, file transfer, package information and plugin queries. Shizuku and Axeron approvals remain separate, as do ADB-shell and root approvals.
- Plugins gain streamed WebUI processes, local KernelSU JavaScript compatibility, lifecycle logs and shortcuts with command consent. Runtime process groups improve cleanup of background children.
- Each app's menu opens a secondary details screen for Android permissions, AppOps, force-stop and battery settings. Immediate individual and bulk Shizuku toggles remain available.
- Update downloads show progress. Android continues to require approval before installation. The signing identity, companion package and existing user data are preserved.

This remains a preview. It does not promise every AxManager API or installer convention. Manager-control APIs, BusyBox-specific installers, network WebUIs, broader third-party/OEM coverage and physical-phone acceptance remain incomplete. See the compatibility matrix and signed acceptance evidence before treating a specific client as supported.

Install in place, then start the host again to load the new runtime.
