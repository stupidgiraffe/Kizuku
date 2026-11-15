Kizuku 0.2.0 Preview 2 adds Shevery 14.6.5 module compatibility and expands app management, automation, and device controls.

- **Apps** puts **All ON** and **All OFF** at the top, with mixed-state and affected-app counts. Both show the targets before applying changes, then report progress and individual failures. Shizuku and Axeron grants stay independent.
- **Modules** accepts Shevery ZIPs alongside Axeron plugins. Run actions and service scripts, review their latest output, or open a local WebUI. Automatic Shevery service startup is optional and updating a module clears that permission. A Shevery WebUI needs fresh consent each time it requests shell or root command access.
- **Automation** supports authenticated explicit intents for start, stop, restart, toggle, and status. Configure it in Settings for use with automation apps. Recovery uses the existing host and respects a manual stop.
- **Labs** can enable or disable installed accessibility services with confirmation, export a Service Doctor snapshot, and manage optional device authentication. Explicitly enrolled devices can suspend third-party apps and block their removal using Android Device Owner policies.

The signed release checks verify update compatibility from 0.2.0 Preview 1, the permanent certificate, and Android 16/17 emulator acceptance as shell and root.

This preview supports a local subset of Shevery WebUI APIs. The module catalog, remote WebUI resources, the native Tasker plugin picker, Profile Owner and third-party Dhizuku API remain outside this release. See [the Shevery comparison](https://github.com/stupidgiraffe/Kizuku/blob/main/docs/UPSTREAMS.md#shevery-1465-comparison) for the source audit and supported features.
