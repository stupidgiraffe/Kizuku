#!/usr/bin/env bash
set -euo pipefail

[[ ${ANDROID_SERIAL:-} == emulator-* ]] || { echo 'Shevery comparison requires a disposable emulator'; exit 1; }
test "$(adb shell id -u | tr -d '\r')" = 2000
package=com.hamondev.shevery
version=14.6.5
evidence=smoke-evidence/shevery
mkdir -p "$evidence"
temp=$(mktemp -d)
trap 'timeout 10 adb logcat -d -s ShizukuServer Shevery AndroidRuntime >"$evidence/logcat.txt" || true; rm -rf "$temp"' EXIT

apk="$temp/shevery-release.apk"
curl --fail --silent --show-error --location --retry 3 --max-time 90   "https://github.com/HmnDev-Tech/shevery/releases/download/$version/shevery-release.apk" -o "$apk"
printf '%s  %s\n' b793b2ecbdbed943c1df6819057d472c461b67ff94011c092b9c9c67b3626bb9 "$apk"   | sha256sum -c | tee "$evidence/apk.txt"
"${ANDROID_HOME:?Set ANDROID_HOME}/build-tools/37.0.0/apksigner" verify --print-certs "$apk"   | tee "$evidence/certificate.txt"
timeout 100 adb install -g "$apk"
timeout 25 adb shell am start -W -n "$package/moe.shizuku.manager.MainActivity"   | tee "$evidence/launcher.txt"

capture_home() {
  local phase=$1 label=$2 coords x y
  for attempt in $(seq 1 14); do
    timeout 10 adb shell uiautomator dump /data/local/tmp/shevery-home.xml >/dev/null || continue
    timeout 10 adb shell cat /data/local/tmp/shevery-home.xml >"$evidence/$phase.xml" || continue
    if grep -Fq "text=\"$label\"" "$evidence/$phase.xml"; then
      timeout 10 adb exec-out screencap -p >"$evidence/$phase.png"
      return 0
    fi
    # Resolve only the official app's startup suggestion or Android's network
    # consent on this fresh emulator. Keep the release APK unchanged.
    if coords=$(python3 - "$evidence/$phase.xml" <<'PY'
import re
import sys
from xml.etree import ElementTree as ET

nodes = list(ET.parse(sys.argv[1]).iter("node"))
prompts = {
    "Allow wireless debugging on this network?": ("com.android.systemui", "Allow"),
    "Enable Persistent TCP Mode?": ("com.hamondev.shevery", "Not Now"),
}
for prompt, (package, button) in prompts.items():
    if not any(n.get("text") == prompt and n.get("package") == package for n in nodes):
        continue
    for n in nodes:
        if n.get("text") != button or n.get("package") != package:
            continue
        bounds = tuple(map(int, re.findall(r"\d+", n.get("bounds", ""))))
        if len(bounds) == 4 and bounds[0] < bounds[2] and bounds[1] < bounds[3]:
            print((bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2)
            sys.exit(0)
sys.exit(1)
PY
    ); then
      read -r x y <<<"$coords"
      timeout 8 adb shell input tap "$x" "$y"
    fi
    sleep 1
  done
  echo "Shevery Home did not present: $label"
  timeout 10 adb exec-out screencap -p >"$evidence/$phase.png" || true
  return 1
}

capture_home stopped 'Shevery is not running'
installed_apk=$(adb shell pm path "$package" | tr -d '\r' | sed -n 's/^package://p')
[[ "$installed_apk" == /data/app/*/base.apk ]]
starter="${installed_apk%/*}/lib/x86_64/libshizuku.so"
timeout 30 adb shell "'$starter' '--apk=$installed_apk'" | tee "$evidence/adb-start.txt"
capture_home shell-running 'Shevery is running'
adb shell ps -Ao UID,PID,PPID,NAME >"$evidence/processes.txt"
grep -Eq '^[[:space:]]*(2000|shell)[[:space:]].*shizuku_server[[:space:]]*$' "$evidence/processes.txt"
adb shell dumpsys package "$package" | sed -n '/versionCode=/p; /versionName=/p' >"$evidence/version.txt"
grep -Eq 'versionCode=1068([[:space:]]|$)' "$evidence/version.txt"
grep -Eq 'versionName=14\.6\.5([.[:space:]]|$)' "$evidence/version.txt"
echo 'Official Shevery 14.6.5 Home and native computer-ADB startup verified.'
