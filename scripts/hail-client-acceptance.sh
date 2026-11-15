#!/usr/bin/env bash
set -euo pipefail

[[ ${ANDROID_SERIAL:-} == emulator-* ]] || { echo 'Hail acceptance requires an emulator'; exit 1; }
evidence=${KIZUKU_EVIDENCE_DIR:?Set the emulator evidence directory}/hail
mkdir -p "$evidence"
manager=io.github.stupidgiraffe.kizuku
probe=$manager.testclient
hail=com.aistra.hail
temp=$(mktemp -d)
trap 'rm -rf "$temp"' EXIT
if [[ "${1:-}" == prepare ]] || ! adb shell pm path "$hail" | grep -q '^package:'; then
  apk="$temp/Hail-v1.11.0.apk"
  curl --fail --silent --show-error --location --retry 3 --max-time 90 \
    https://github.com/aistra0528/Hail/releases/download/v1.11.0/Hail-v1.11.0.apk -o "$apk"
  printf '%s  %s\n' 3be0c4a210b0472198ea3b675fcbd5720fa16288dcff119557644ea947003236 "$apk" \
    | sha256sum -c | tee "$evidence/fixture.txt"
  timeout 100 adb install -r "$apk"
fi
if [[ "${1:-}" == prepare ]]; then exit 0; fi
trap 'timeout 10 adb logcat -d -s Hail AndroidRuntime KizukuServer >"$evidence/logcat.txt" || true; timeout 20 adb uninstall "$hail" || true; rm -rf "$temp"' EXIT

wait_for_control() {
  local label=$1 action=$2 screenshot="$evidence/ui.png" coords x y
  for attempt in $(seq 1 10); do
    timeout 8 adb shell uiautomator dump /data/local/tmp/kizuku-hail.xml >/dev/null || continue
    timeout 8 adb shell cat /data/local/tmp/kizuku-hail.xml >"$evidence/ui.xml" || continue
    if coords=$(python3 - "$evidence/ui.xml" "$label" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
for node in root.iter("node"):
    if sys.argv[2] in (node.get("text"), node.get("content-desc")):
        bounds = tuple(map(int, re.findall(r"\d+", node.get("bounds", ""))))
        if len(bounds) == 4 and bounds[0] < bounds[2] and bounds[1] < bounds[3]:
            print((bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2)
            sys.exit(0)
sys.exit(1)
PY
    ); then
      if [[ "$action" == tap ]]; then read -r x y <<<"$coords"; timeout 8 adb shell input tap "$x" "$y"; fi
      return 0
    fi
  done
  timeout 10 adb exec-out screencap -p >"$screenshot" || true
  echo "Hail control not found: $label" >&2
  return 1
}

timeout 25 adb shell am start -W -n "$hail/.ui.main.MainActivity"
wait_for_control 'Settings' tap
wait_for_control 'Working mode' tap
wait_for_control 'Shizuku - Force Stop' tap
host_uid=$(adb shell id -u | tr -d '\r')
timeout 50 adb shell am instrument -w -e class "$manager.ThirdPartyClientTest" -e host_uid "$host_uid" \
  "$manager.test/androidx.test.runner.AndroidJUnitRunner" | tee "$evidence/authorization.txt"
grep -Fq 'OK (1 test)' "$evidence/authorization.txt"
wait_for_control 'Shizuku - Force Stop' read
timeout 10 adb exec-out screencap -p >"$evidence/authorized.png"

# Only Hail invokes forceStopPackage on the disposable probe. Confirm its
# foreground process really existed first and the package reaches stopped=true.
timeout 25 adb shell am start -W -n "$probe/.ProbeActivity"
timeout 10 adb shell pidof "$probe"
timeout 25 adb shell am start -W -n "$hail/.ui.api.ApiActivity" -a android.intent.action.SHOW_APP_INFO \
  --es android.intent.extra.PACKAGE_NAME "$probe"
wait_for_control 'Freeze' tap
for attempt in $(seq 1 20); do
  state=$(timeout 8 adb shell dumpsys package "$probe" | grep -E '^[[:space:]]*User 0:')
  if [[ "$state" == *'stopped=true'* ]] && ! timeout 8 adb shell pidof "$probe"; then
    printf '%s\n' "$state" >"$evidence/frozen.txt"
    echo "Hail used Kizuku's Shizuku connection to force-stop the test app (host UID $host_uid)."
    exit 0
  fi
  sleep 0.5
done
timeout 10 adb exec-out screencap -p >"$evidence/freeze-failure.png" || true
echo 'Hail did not force-stop the disposable probe' >&2
exit 1
