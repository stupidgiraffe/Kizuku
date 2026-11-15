#!/usr/bin/env bash
set -euo pipefail

[[ ${ANDROID_SERIAL:-} == emulator-* ]] || { echo 'Signed update acceptance requires an emulator'; exit 1; }
test "$(adb shell id -u | tr -d '\r')" = 2000

artifacts=${1:?Pass the signed artifact directory}
evidence=smoke-evidence/signed-update
mkdir -p "$evidence"
manager=io.github.stupidgiraffe.kizuku
probe=$manager.testclient
build_tools=${ANDROID_HOME:?Set ANDROID_HOME}/build-tools/37.0.0
candidate=$artifacts/manager/build/outputs/apk/release/manager-release.apk
probe_apk=$artifacts/test-client/build/outputs/apk/release/test-client-release.apk
instrumentation=$artifacts/manager/build/outputs/apk/androidTest/release/manager-release-androidTest.apk
temp=$(mktemp -d)
trap 'rm -rf "$temp"' EXIT

preview=$temp/Kizuku-v0.2.0-preview.1.apk
preview_code=4
preview_hash=47c49d93dc0ecef1b87e088182212ec8eb0039489b7fd5cc434aa739a829198c
fingerprint=83c9b70c57b2666f8eb48f0cfe74013053c804fd0b452addfdeed451629d2bce
curl --fail --silent --show-error --location --retry 3 --max-time 90 \
  https://github.com/stupidgiraffe/Kizuku/releases/download/v0.2.0-preview.1/Kizuku-v0.2.0-preview.1.apk \
  --output "$preview"
printf '%s  %s\n' "$preview_hash" "$preview" | sha256sum -c | tee "$evidence/fixture.txt"

for apk in "$preview" "$candidate"; do
  signature=$("$build_tools/apksigner" verify --print-certs "$apk")
  grep -Fq "certificate SHA-256 digest: $fingerprint" <<<"$signature"
done

manifest=$("$build_tools/aapt2" dump badging "$candidate" | sed -n '1p')
manager_header="package: name='$manager' versionCode='"
[[ "$manifest" == "$manager_header"* ]] || { echo 'Candidate manifest has an unexpected package'; exit 1; }
candidate_code=${manifest#"$manager_header"}
candidate_code=${candidate_code%%\'*}
[[ $candidate_code =~ ^[1-9][0-9]*$ ]] || { echo 'Candidate manifest has no valid manager versionCode'; exit 1; }
minimum_code=$preview_code
if [[ ${KIZUKU_REQUIRE_NEWER_VERSION:-false} == true ]]; then
  minimum_code=$((preview_code + 1))
fi
if (( candidate_code < minimum_code )); then
  echo "Candidate versionCode $candidate_code must be at least $minimum_code"
  exit 1
fi
printf '0.2.0 Preview 1 versionCode %s to candidate versionCode %s\n' "$preview_code" "$candidate_code" | tee "$evidence/versions.txt"

if adb shell pm path "$manager" | grep -q '^package:'; then
  echo 'Start signed update acceptance with the manager absent'
  exit 1
fi

instrument() {
  timeout 60 adb shell am instrument -w -e class "$manager.SignedUpdateTest" \
    -e stage "$1" "$manager.test/androidx.test.runner.AndroidJUnitRunner" | tee "$evidence/$1.txt"
  grep -Fq 'OK (1 test)' "$evidence/$1.txt"
  tr -d '\r' <"$evidence/$1.txt" | sed -n 's/.*INSTRUMENTATION_STATUS: kizuku_adb_identity=//p' >"$evidence/$1-adb-identity.txt"
  grep -Exq '[A-Za-z0-9+/]{43}=' "$evidence/$1-adb-identity.txt"
}

record_installation() {
  local installed_path installed_hash expected_hash
  adb shell dumpsys package "$manager" >"$evidence/package-$1.txt"
  grep -Eq "^[[:space:]]*versionCode=$2([[:space:]]|$)" "$evidence/package-$1.txt"
  adb shell pm list packages -U "$manager" | tr -d '\r' | grep -F "package:$manager uid:" >"$evidence/uid-$1.txt"
  installed_path=$(adb shell pm path "$manager" | tr -d '\r')
  [[ "$installed_path" == package:/data/app/*/base.apk ]]
  installed_hash=$(adb shell sha256sum "${installed_path#package:}" | tr -d '\r')
  expected_hash=$(sha256sum "$3")
  test "${installed_hash%% *}" = "${expected_hash%% *}"
  printf '%s\n' "$installed_hash" >"$evidence/apk-$1.sha256"
}

timeout 120 adb install "$preview"
timeout 120 adb install -t "$probe_apk"
timeout 120 adb install -t "$instrumentation"
record_installation before "$preview_code" "$preview"
instrument before
timeout 120 adb install -r "$candidate" | tee "$evidence/replace.txt"
record_installation after "$candidate_code" "$candidate"
cmp "$evidence/uid-before.txt" "$evidence/uid-after.txt"
instrument after
cmp "$evidence/before-adb-identity.txt" "$evidence/after-adb-identity.txt"

# Run the separate fresh-install smoke suites with an empty app state.
adb uninstall "$manager.test"
adb uninstall "$probe"
adb uninstall "$manager"
