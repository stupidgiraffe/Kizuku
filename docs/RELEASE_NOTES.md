# Kizuku 0.2.0 Preview 3

- Pair with Wireless debugging through an opt-in Android Accessibility session or the existing pairing-code notification.
- Manage separate remembered approvals for ADB-shell and root hosts. Previous approvals carry over to ADB shell.
- Export a custom-path SU script through Settings → rish terminal access. It runs through Kizuku's current host after checking the requested UID.
- Interactive Axeron terminals support input, merged output, resize and truthful exit status.
- Device Owner and eligible Profile Owner setups expose Android package-access delegation for Dhizuku clients. Older clients use the optional Dhizuku companion, managed in Labs → Device policy.
- Upstream release monitoring tracks exact tags and commits for the reference apps.

Dhizuku supports package access. Raw Binder forwarding and Dhizuku UserService
execution are unavailable. Axeron native sockets, file service and donor-specific
WebUI endpoints are outside this preview. Android and OEM restrictions still
apply to wireless debugging and multi-user enrollment.
