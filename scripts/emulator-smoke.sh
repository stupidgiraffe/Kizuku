#!/usr/bin/env bash
set -euo pipefail
artifact_root=${1:-.}
evidence=${KIZUKU_EVIDENCE_DIR:-smoke-evidence}
mkdir -p "$evidence"
host_uid=$(adb shell id -u | tr -d '\r')
manager=io.github.stupidgiraffe.kizuku
client=io.github.stupidgiraffe.kizuku.testclient
trap 'timeout 10 adb logcat -d -s KizukuServer AndroidRuntime JavaBinder >"$evidence"/logcat.txt; timeout 10 adb shell cat /data/local/tmp/kizuku-smoke.log >"$evidence"/server.txt 2>/dev/null || true' EXIT
adb shell df -h /data >"$evidence"/storage.txt
while IFS= read -r apk; do adb install -r -t "$apk"; done < <(find "$artifact_root" -name '*.apk' -type f ! -path '*/androidTest/*' | sort)
while IFS= read -r apk; do adb install -r -t "$apk"; done < <(find "$artifact_root" -name '*.apk' -type f -path '*/androidTest/*' | sort)
if [[ "${KIZUKU_TEST_ADB:-false}" == true && "$host_uid" == 2000 ]]; then
  KIZUKU_EVIDENCE_DIR="$evidence/adb" scripts/adb-acceptance.sh
fi
manager=io.github.stupidgiraffe.kizuku
client=io.github.stupidgiraffe.kizuku.testclient
launch() {
  adb shell "apk=\$(pm path $manager | head -n1 | cut -d: -f2-); CLASSPATH=\"\$apk\" app_process /system/bin --nice-name=kizuku_server $manager.server.ServerMain \"\$apk\" >/data/local/tmp/kizuku-smoke.log 2>&1 </dev/null &"
}
instrument() {
  local package=$1 argument=$2 value=$3
  local class="$package.ManagerSmokeTest"
  if [[ "$package" == "$client" ]]; then class="$package.ProtocolSmokeTest"; fi
  adb shell am instrument -w -e class "$class" -e host_uid "$host_uid" -e "$argument" "$value" "$package.test/androidx.test.runner.AndroidJUnitRunner" | tee "$evidence/$argument-$value.txt"
  grep -E 'OK \(1 test\)' "$evidence/$argument-$value.txt"
}
probe() { adb shell am force-stop "$client"; instrument "$client" stage "$1"; }
adb shell dumpsys package "$manager" >"$evidence"/manager-package.txt
adb shell am start -W --user 0 -n "$manager/.MainActivity"
adb shell screencap -p /data/local/tmp/kizuku-stopped.png
adb pull /data/local/tmp/kizuku-stopped.png "$evidence"/stopped.png
launch
instrument "$manager" action connected
instrument "$manager" action stale
probe denied
instrument "$manager" action legacy
probe legacy
instrument "$manager" action native
probe native
instrument "$manager" action revoke
probe revoked
instrument "$manager" action persist
instrument "$manager" action stop
launch
instrument "$manager" action connected
probe persisted
adb shell content call --uri "content://$manager.core" --method save_grants --extra 'grants:s:[]' 2>&1 | tee "$evidence"/shell-write-denied.txt || true
grep -F 'Unknown controller method' "$evidence"/shell-write-denied.txt
probe persisted
adb shell am start -W -n "$manager/.MainActivity"
sleep 1
adb shell screencap -p /data/local/tmp/kizuku-core.png
adb pull /data/local/tmp/kizuku-core.png "$evidence"/core.png
instrument "$manager" action stop
launch
instrument "$manager" action connected
adb uninstall "$manager"
for attempt in $(seq 1 40); do
  if ! adb shell pidof kizuku_server >/dev/null; then echo 'Manager removal terminated its privileged host'; exit 0; fi
  sleep 0.25
done
echo 'Privileged host survived removal of its manager'
exit 1
