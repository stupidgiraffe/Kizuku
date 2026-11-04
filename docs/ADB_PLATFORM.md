# Local ADB platform notes — 2026-11-01

Implementation evidence, not runtime acceptance. Context7 `/websites/developer_android`
was queried for NSD/permissions; Android/AOSP sources were then read directly.

## Permissions and discovery

- For this target-37 app on API 37+, declare `ACCESS_LOCAL_NETWORK`, check it at
  each user-started attempt, and request it through the Activity permission API
  before NSD or LAN sockets. It is dangerous and belongs to `NEARBY_DEVICES`;
  another granted permission in that group may avoid a new prompt. Denial or
  revocation must produce a recoverable unavailable state. Keep `INTERNET`.
  TCP denial can look like a timeout. [Android local network guide][lnp]
- Android 16 restriction is opt-in: `am compat enable RESTRICT_LOCAL_NETWORK
  PACKAGE`, then reboot; its temporary gate is `NEARBY_WIFI_DEVICES`. NSD calls
  outside the app process were unaffected by that opt-in. Do not request the
  API-37 permission on older OS versions. [Android local network guide][lnp]
- Prefer `registerServiceInfoCallback(DiscoveryRequest, executor, callback)` on
  API 37. On API 34+ use discovery plus per-service
  `registerServiceInfoCallback(NsdServiceInfo, ...)`; older devices can use
  `discoverServices(type, PROTOCOL_DNS_SD, listener)` plus `resolveService`.
  Use updated host addresses and their `Network`. One-shot resolution is deprecated
  at 34 because results become stale. The NSD
  overview says combined discovery needs T extension 22, while its method entry
  says 7: require API 37 or T extension 22 for the combined API. [NsdManager][nsd]
- With broad permission, specify `FLAG_NO_PICKER` on supported discovery requests.
  Without permission it fails with `FAILURE_PERMISSION_DENIED`. Omitting both picker
  flags can automatically show a picker if permission is absent.
  `FLAG_SHOW_PICKER` provides a separate
  per-service grant; `FLAG_USER_APPROVED_ONLY` finds earlier approvals. The flags
  are API 37 / T extension 22. [DiscoveryRequest][request]
- Stop discovery with its original listener; unregister service-info callbacks.
  Reuse only after their stopped/unregistered callback. Track pending starts and
  ignore stale callbacks after cancel/service loss. NSD may throw when stopping
  an unregistered listener. Before T extension 7, foreground Wi-Fi discovery needs
  a multicast lock; newer foreground NSD manages this. [NsdManager][nsd]

## Pairing, starting and cancellation

- Discover `_adb-tls-pairing._tcp.` and `_adb-tls-connect._tcp.` separately.
  Upstream NSD constants omit the final dot; normalize it when comparing types.
  Pairing port and connection port are different. After pairing, rediscover or
  use an already observed connect service: its advertisement can predate pairing.
  Classic `_adb._tcp` / `adb tcpip PORT` is an unencrypted separate transport.
  [ADB Wi-Fi design][wifi], [Nightzuku AdbMdns][mdns-source]
- Launch `Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS`, catch unavailable
  handlers, and fall back to `Settings.ACTION_SETTINGS`; guide the user to
  Wireless debugging → Pair device with pairing code. Do not assume an OEM
  private activity or a public wireless-debugging action exists. [Settings][settings]
- AOSP dismisses the code dialog on `onPause()` and disables pairing on dismissal.
  Keep Settings visible during code entry using notification reply or split
  screen. Returning to the app can invalidate code/port. [WirelessDebuggingFragment][fragment]
- Notification reply needs a mutable, explicit `PendingIntent`; use a session
  identity, because extras do not distinguish pending intents. Cancel old actions
  when their endpoint/session expires. [Direct reply guide][reply]
- On API 33+, request `POST_NOTIFICATIONS` for the reply workflow: denied FGS
  notifications appear in Task Manager, not the notification drawer. [Notification permission][notifications]
- Start any FGS from the visible Activity/user action; background starts are
  restricted from API 31. API 34+ needs an appropriate declared FGS type and its
  permission. `connectedDevice` additionally needs a listed prerequisite (such as
  declared `CHANGE_WIFI_MULTICAST_STATE`); `ACCESS_LOCAL_NETWORK` alone is not
  listed. Bounded `shortService` has an approximately three-minute timeout and
  requires timeout cleanup. [FGS start restrictions][fgs-start], [FGS types][fgs]
- Engineering requirements: only accept local interface/loopback addresses and
  valid ports; mDNS names prove no identity. Keep one attempt, validate six digits,
  never log/store the code, and keep the ADB key in app-private non-backup storage.
  Close native pairing context and sockets on cancellation; coroutine cancellation
  alone does not interrupt blocking I/O. Bound connect/read/handshake and total
  discovery time. Nightzuku uses 10s connect, 15s pairing read, 30s ADB read and
  caps frames at 4 MiB (reference choices). [ADB client][client-source], [pairing client][pair-source]

## CI emulator acceptance

Use a disposable API 30+ phone AVD with Wi-Fi connected. Probe
`adb -s SERIAL shell cmd adb is-wifi-supported`; fail or explicitly skip unsupported
images. AOSP `cmd adb` only exposes support queries, not a start-pairing command.
Drive Developer Options and the code dialog using UIAutomator; read the fresh
code/port only into transient test memory and leave the dialog open. Exercise the
app's own key, pairing transport, connect discovery, shell startup and live Binder
verification; then cancel, use a wrong code, toggle debugging and test reconnect.
Host `adb pair` tests the host key; existing emulator TCP access does not establish
pairing acceptance. SDK 37 permission grant/deny/revoke needs an API 37 run.
Preview 2's [signed Android 17 run](https://github.com/stupidgiraffe/Kizuku/actions/runs/41845954990) passed notification pairing, saved-key reconnect, custom TCP, local-network denial and cancellation. [ADB shell command][cmd], [wireless workflow][adb]

[lnp]: https://developer.android.com/privacy-and-security/local-network-permission
[nsd]: https://developer.android.com/reference/android/net/nsd/NsdManager
[request]: https://developer.android.com/reference/android/net/nsd/DiscoveryRequest
[wifi]: https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/dev/adb_wifi.md
[settings]: https://developer.android.com/reference/android/provider/Settings#ACTION_APPLICATION_DEVELOPMENT_SETTINGS
[fragment]: https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/src/com/android/settings/development/WirelessDebuggingFragment.java
[reply]: https://developer.android.com/develop/ui/compose/notifications/create-notification#direct-reply
[notifications]: https://developer.android.com/develop/ui/compose/notifications/notification-permission
[fgs-start]: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
[fgs]: https://developer.android.com/develop/background-work/services/fgs/service-types
[cmd]: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/adb/AdbShellCommand.java
[adb]: https://developer.android.com/tools/adb
[mdns-source]: https://github.com/kerneldroid/Nightzuku/blob/67cf6d300b4895bcd4e3cf5538faa4782ca2371a/manager/src/main/java/moe/shizuku/manager/adb/AdbMdns.kt
[client-source]: https://github.com/kerneldroid/Nightzuku/blob/67cf6d300b4895bcd4e3cf5538faa4782ca2371a/manager/src/main/java/moe/shizuku/manager/adb/AdbClient.kt
[pair-source]: https://github.com/kerneldroid/Nightzuku/blob/67cf6d300b4895bcd4e3cf5538faa4782ca2371a/manager/src/main/java/moe/shizuku/manager/adb/AdbPairingClient.kt

## Pairing wire checks

- `SSLSockets.exportKeyingMaterial` is public from API **31**; null context differs
  from empty context. [Official API](https://developer.android.com/reference/android/net/ssl/SSLSockets#exportKeyingMaterial(javax.net.ssl.SSLSocket,%20java.lang.String,%20byte[],%20int))
- AOSP uses exporter label `adb-label\0` (10 bytes: C array `sizeof` includes NUL),
  no context, and exports 64 bytes appended to the pairing code before SPAKE2.
  [TLS source](https://android.googlesource.com/platform/packages/modules/adb/+/ef5ecc5ae3b084748ee82fa322a23239e864cba8/tls/tls_connection.cpp), [pairing source](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/pairing_connection/pairing_connection.cpp)
- Packed `PeerInfo` is 8192 bytes: type byte + 8191 bytes data; `ADB_RSA_PUB_KEY=0`,
  `ADB_DEVICE_GUID=1`. [Header](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/pairing_connection/include/adb/pairing/pairing_connection.h)
- AOSP UI selectors: `com.android.settings:id/pairing_code` and `:id/ip_addr`.
  Wait for asynchronous values without logging them; keep dialog visible.
  [Controller](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/src/com/android/settings/development/AdbWirelessDialogController.java)

## New uploaded reference provenance and narrow reliability lessons

`shizuku-v14.0.31-next-debug.apk` is the exact published Shizuku-Next asset:
local SHA-256 `8a687ba3dbcfad62a44b6e04862fcbc0fcd67519efffa81403ef2f6fcb46a270`
matches the [GitHub release asset digest](https://api.github.com/repos/rushiranpise/Shizuku-Next/releases/tags/v14.0.31-next).
Manifest/resources identify `moe.shizuku.privileged.api`, `14.0.31-next`/1977,
label `Shizuku-Next`; no classes were decompiled. Source tag
[`v14.0.31-next`](https://github.com/rushiranpise/Shizuku-Next/releases/tag/v14.0.31-next)
resolves to `c97dc7d23c94cee90748128160c4a3366983aa12`.

- [AdbMdns](https://github.com/rushiranpise/Shizuku-Next/blob/c97dc7d23c94cee90748128160c4a3366983aa12/manager/src/main/java/moe/shizuku/manager/adb/AdbMdns.kt)
  queues legacy NSD resolves. Preserve session generations and drain in `finally`:
  that reference still returns on null host before draining its queue.
- [WirelessPortHunt](https://github.com/rushiranpise/Shizuku-Next/blob/c97dc7d23c94cee90748128160c4a3366983aa12/manager/src/main/java/moe/shizuku/manager/adb/WirelessPortHunt.kt)
  keeps discovery alive until connection and selects the latest non-excluded port.
  Adapt this principle without its forced-hotspot/settings-write feature.
- [WirelessPortRetry](https://github.com/rushiranpise/Shizuku-Next/blob/c97dc7d23c94cee90748128160c4a3366983aa12/manager/src/main/java/moe/shizuku/manager/start/WirelessPortRetry.kt)
  caps fresh-port searches at three and forbids replay after command submission.
  Keep genuine key rejection separate from stale-port/transport failure; its
  string matching is reference policy, not authoritative TLS error taxonomy.
- [AdbStarter](https://github.com/rushiranpise/Shizuku-Next/blob/c97dc7d23c94cee90748128160c4a3366983aa12/manager/src/main/java/moe/shizuku/manager/adb/AdbStarter.kt)
  accepts disconnect after `tcpip:` as a possible daemon restart and then verifies
  the intended listener. A socket-open check still needs an ADB handshake.
- Do not import its [AdbClient](https://github.com/rushiranpise/Shizuku-Next/blob/c97dc7d23c94cee90748128160c4a3366983aa12/manager/src/main/java/moe/shizuku/manager/adb/AdbClient.kt)
  wholesale: local `socket` shadows the owned field, reads have no `SO_TIMEOUT`,
  and peer length reaches allocation without a size cap. Keep Kizuku's bounds.
