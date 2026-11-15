#!/usr/bin/env bash
set -euo pipefail

[[ ${ANDROID_SERIAL:-} == emulator-* ]] || { echo 'Device Owner acceptance requires a disposable emulator'; exit 1; }
test "$(adb shell id -u | tr -d '\r')" = 2000
artifact_root=${1:?Pass the APK artifact directory}
evidence=smoke-evidence/device-owner
mkdir -p "$evidence"
manager=io.github.stupidgiraffe.kizuku
client=$manager.testclient

variant=debug
if [[ -f "$artifact_root/manager/build/outputs/apk/release/manager-release.apk" ]]; then
  variant=release
fi
for path in \
  "manager/build/outputs/apk/$variant/manager-$variant.apk" \
  "test-client/build/outputs/apk/$variant/test-client-$variant.apk" \
  "manager/build/outputs/apk/androidTest/$variant/manager-$variant-androidTest.apk"; do
  timeout 100 adb install -t "$artifact_root/$path"
done

verify_owner() {
  timeout 60 adb shell am instrument -w -e class "$manager.DeviceOwnerPolicyTest" -e scenario "$1" \
    "$manager.test/androidx.test.runner.AndroidJUnitRunner" | tee "$evidence/$1.txt"
  grep -Fq 'OK (1 test)' "$evidence/$1.txt"
}

verify_owner unprovisioned
timeout 30 adb shell dpm set-device-owner --user 0 "$manager/.KizukuDeviceAdminReceiver" \
  | tee "$evidence/provision.txt"
verify_owner enrolled
timeout 15 adb shell dpm list-owners | tee "$evidence/after.txt"
if grep -Fq "$manager" "$evidence/after.txt"; then
  echo 'Device Owner acceptance did not restore the original owner state' >&2
  exit 1
fi
adb uninstall "$manager.test"
adb uninstall "$client"
adb uninstall "$manager"
