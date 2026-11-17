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
# Amarok uses the legacy Dhizuku 2.4 provider and real Android package delegation.
compat="com.rosan.dhizuku"
amarok="deltazero.amarok.foss"
apk="$evidence/Amarok-v0.10.1-foss.apk"
curl --fail --silent --show-error --location --retry 3 --max-time 90 \
  https://github.com/deltazefiro/Amarok-Hider/releases/download/v0.10.1/Amarok-v0.10.1-foss.apk \
  --output "$apk"
printf '%s  %s\n' 821c71eda949d0627393727a6462f2ac573c0381547ed384ce740096bd58a7c8 "$apk" \
  | sha256sum -c | tee "$evidence/amarok-asset-sha256.txt"
adb install -t "$artifact_root/dhizuku-compat/build/outputs/apk/$variant/dhizuku-compat-$variant.apk"
adb install "$apk"
rm "$apk"
amarok_status=0
timeout 90 adb shell am instrument -w -e class "$manager.DhizukuClientTest" \
  "$manager.test/androidx.test.runner.AndroidJUnitRunner" | tee "$evidence/dhizuku-amarok.txt" || amarok_status=1
grep -Fq 'OK (1 test)' "$evidence/dhizuku-amarok.txt" || amarok_status=1
adb uninstall "$amarok"
adb uninstall "$compat"
verify_owner enrolled
timeout 15 adb shell dpm list-owners | tee "$evidence/after.txt"
if grep -Fq "$manager" "$evidence/after.txt"; then
  echo 'Device Owner acceptance did not restore the original owner state' >&2
  exit 1
fi
profile_result=$(timeout 30 adb shell pm create-user --profileOf 0 --managed KizukuAcceptance | tr -d '\r')
printf '%s\n' "$profile_result" | tee "$evidence/profile-created.txt"
profile=$(sed -n 's/^Success: created user id \([0-9][0-9]*\)$/\1/p' <<<"$profile_result")
[[ "$profile" =~ ^[1-9][0-9]+$ ]]
trap 'adb shell pm remove-user "$profile" >/dev/null || true' EXIT
for package in "$manager" "$client" "$manager.test"; do
  adb shell cmd package install-existing --user "$profile" "$package"
done
timeout 40 adb shell am start-user -w "$profile"
timeout 30 adb shell dpm set-profile-owner --user "$profile" "$manager/.KizukuDeviceAdminReceiver" \
  | tee "$evidence/profile-provisioned.txt"
timeout 60 adb shell am instrument --user "$profile" -w -e class "$manager.DeviceOwnerPolicyTest" -e scenario profile \
  "$manager.test/androidx.test.runner.AndroidJUnitRunner" | tee "$evidence/profile.txt"
grep -Fq 'OK (1 test)' "$evidence/profile.txt"
# Android completes managed-profile removal asynchronously. Wait for user and
# policy cleanup before uninstalling the former profile owner.
timeout 65 adb shell pm remove-user --wait "$profile"
trap - EXIT
for attempt in {1..20}; do
  timeout 5 adb shell dpm list-owners >"$evidence/after-profile.txt"
  if ! grep -Fq "$manager" "$evidence/after-profile.txt"; then
    break
  fi
  sleep 1
done
cat "$evidence/after-profile.txt"
if grep -Fq "$manager" "$evidence/after-profile.txt"; then
  echo 'Managed profile removal left a Kizuku owner record' >&2
  exit 1
fi
adb uninstall "$manager.test"
adb uninstall "$client"
adb uninstall "$manager"
exit "$amarok_status"
