#!/usr/bin/env bash
set -euo pipefail
artifact_root=${1:-.}
mkdir -p smoke-evidence
manager=io.github.stupidgiraffe.kizuku
client=io.github.stupidgiraffe.kizuku.testclient
trap 'adb logcat -d -s KizukuServer AndroidRuntime >smoke-evidence/logcat.txt; adb shell cat /data/local/tmp/kizuku-smoke.log >smoke-evidence/server.txt 2>/dev/null || true' EXIT
adb shell df -h /data >smoke-evidence/storage.txt
while IFS= read -r apk; do adb install -r -t "$apk"; done < <(find "$artifact_root" -name '*debug*.apk' -type f ! -path '*/androidTest/*' | sort)
while IFS= read -r apk; do adb install -r -t "$apk"; done < <(find "$artifact_root" -name '*debug*.apk' -type f -path '*/androidTest/*' | sort)
manager=io.github.stupidgiraffe.kizuku
client=io.github.stupidgiraffe.kizuku.testclient
launch() {
  adb shell "apk=\$(pm path $manager | head -n1 | cut -d: -f2-); CLASSPATH=\"\$apk\" app_process /system/bin --nice-name=kizuku_server $manager.server.ServerMain \"\$apk\" >/data/local/tmp/kizuku-smoke.log 2>&1 </dev/null &"
}
instrument() {
  local package=$1 argument=$2 value=$3
  adb shell am instrument -w -e "$argument" "$value" "$package.test/androidx.test.runner.AndroidJUnitRunner" | tee "smoke-evidence/$argument-$value.txt"
  rg 'OK \([0-9]+ test' "smoke-evidence/$argument-$value.txt"
}
probe() { adb shell am force-stop "$client"; instrument "$client" stage "$1"; }
adb shell am start -n "$manager/.MainActivity"
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
adb shell content call --uri "content://$manager.core" --method save_grants --extra 's:grants:[]' 2>&1 | tee smoke-evidence/shell-write-denied.txt
rg 'Unknown controller method' smoke-evidence/shell-write-denied.txt
probe persisted
adb shell am start -n "$manager/.MainActivity"
adb shell screencap -p /data/local/tmp/kizuku-core.png
adb pull /data/local/tmp/kizuku-core.png smoke-evidence/core.png
instrument "$manager" action stop
