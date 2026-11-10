#!/usr/bin/env bash
set -euo pipefail
manager=io.github.stupidgiraffe.kizuku
evidence=${KIZUKU_EVIDENCE_DIR:-smoke-evidence/adb}
mkdir -p "$evidence"
# adb normally waits forever for an offline selected transport, including in traps.
adb_binary=$(type -P adb)
adb() { timeout 60 "$adb_binary" "$@"; }
instrument() {
  echo "ADB acceptance: $1"
  timeout 140 "$adb_binary" shell am instrument -w -e class "$manager.AdbActivationTest" -e scenario "$1" "$manager.test/androidx.test.runner.AndroidJUnitRunner" \
    | tee "$evidence/$1.txt" && grep -E 'OK \(1 test\)' "$evidence/$1.txt"
}
restore_transport() {
  timeout 10 "$adb_binary" logcat -d -s KizukuAdb AdbPairClient >"$evidence/pairing.log" 2>/dev/null || true
  timeout 10 "$adb_binary" shell 'tail -c 16384 /data/local/tmp/kizuku-start.log' >"$evidence/startup.log" 2>/dev/null || true
  timeout 10 "$adb_binary" tcpip 5555 >/dev/null 2>&1 || true
  export ANDROID_SERIAL=emulator-5554
  timeout 30 adb wait-for-device || true
}
# A fresh emulator owns this custom port; no workstation or physical phone involved.
adb emu redir add tcp:37055:37055
adb tcpip 37055 || true # Restarting adbd can close the original transport before its reply.
export ANDROID_SERIAL=127.0.0.1:37055
trap restore_transport EXIT
for attempt in $(seq 1 30); do
  timeout 3 "$adb_binary" connect 127.0.0.1:37055 >/dev/null 2>&1 || true
  if timeout 2 "$adb_binary" shell true 2>/dev/null; then break; fi
  sleep 1
done
timeout 30 adb wait-for-device
failed=0
if ! instrument apps; then
  failed=1
  # A failed revocation assertion can leave a persisted test grant.
  adb shell pm clear "$manager"
fi
adb shell pm grant "$manager" android.permission.POST_NOTIFICATIONS
api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
if (( api >= 37 )); then
  adb shell pm revoke "$manager" android.permission.ACCESS_LOCAL_NETWORK
  instrument denied
  adb shell pm grant "$manager" android.permission.ACCESS_LOCAL_NETWORK
fi
instrument cancel
if ! instrument tcp; then
  failed=1
  adb shell 'tail -c 16384 /data/local/tmp/kizuku-start.log' >"$evidence/tcp-startup.log" 2>/dev/null || true
  adb shell 'pids=$(pidof kizuku_server); [ -z "$pids" ] || kill $pids'
fi
adb shell settings put global development_settings_enabled 1
adb shell cmd adb is-wifi-supported | tee "$evidence/wifi-supported.txt"
grep -qx 'true' "$evidence/wifi-supported.txt"
instrument pair
# A new instrumentation process reopens the encrypted persisted ADB identity.
instrument reconnect
adb shell am start -W -n "$manager/.MainActivity"
adb shell screencap -p /data/local/tmp/kizuku-adb-complete.png
adb pull /data/local/tmp/kizuku-adb-complete.png "$evidence/complete.png"
exit "$failed"
