#!/usr/bin/env bash
set -euo pipefail
mkdir -p smoke-evidence
export ANDROID_USER_HOME="${RUNNER_TEMP:-/tmp}/kizuku-android"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
export ANDROID_SERIAL=emulator-5554
target=${KIZUKU_EMULATOR_TARGET:-android-37.0}
case "$target" in
  android-36) expected_api=36 ;;
  android-37.0) expected_api=37 ;;
  *) echo "Unsupported emulator target: $target"; exit 1 ;;
esac
mkdir -p "$ANDROID_AVD_HOME"
printf 'no\n' | avdmanager create avd --force -n kizuku-ci --path "$ANDROID_AVD_HOME/kizuku-ci.avd" --package "system-images;$target;google_apis;x86_64" --device pixel_7
avdmanager list avd >smoke-evidence/avds.txt
cp "$ANDROID_AVD_HOME/kizuku-ci.ini" smoke-evidence/avd-target.ini
grep -Fxq "target=$target" smoke-evidence/avd-target.ini
test -f "$ANDROID_AVD_HOME/kizuku-ci.avd/config.ini"
printf 'disk.dataPartition.size=6G\n' >>"$ANDROID_AVD_HOME/kizuku-ci.avd/config.ini"
"$ANDROID_HOME/emulator/emulator" -port 5554 -avd kizuku-ci -no-window -gpu software -no-snapshot -noaudio -no-boot-anim -camera-back none -memory 2048 -partition-size 6144 >smoke-evidence/emulator.log 2>&1 &
emulator_pid=$!
cleanup() {
  set +e
  # Android's debug image logs temporary pairing codes under this system tag.
  timeout 10 adb logcat -d -b crash -b system 'AdbDebuggingManager:S' '*:V' >smoke-evidence/framework.log
  timeout 10 adb shell df -h /data >smoke-evidence/storage.txt
  adb emu kill
  kill "$emulator_pid" 2>/dev/null
}
trap cleanup EXIT
timeout 120 adb wait-for-device
# sys.boot_completed alone was observed true before input/settings were ready.
# Require the actual package, activity, input and settings command services.
ready=0
for attempt in $(seq 1 90); do
  services=$(adb shell cmd -l 2>/dev/null | tr -d '\r' || true)
  if [[ $(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]] &&
      grep -qE '^[[:space:]]+package$' <<<"$services" &&
      grep -qE '^[[:space:]]+activity$' <<<"$services" &&
      grep -qE '^[[:space:]]+input$' <<<"$services" &&
      grep -qE '^[[:space:]]+settings$' <<<"$services"; then
    ready=$((ready+1))
    if (( ready >= 3 )); then break; fi
  else ready=0; fi
  sleep 2
done
if (( ready < 3 )); then echo 'Android framework services did not become stable'; exit 1; fi
adb shell getprop ro.build.version.sdk | tr -d '\r' | tee smoke-evidence/android-api.txt
grep -Fxq "$expected_api" smoke-evidence/android-api.txt
# The previous image restarted SurfaceFlinger/system_server during first boot and
# left user 0 locked. An installed CE-only activity cannot resolve until unlock.
adb shell am unlock-user 0 || true
for attempt in $(seq 1 60); do
  user_state=$(adb shell am get-started-user-state 0 2>/dev/null | tr -d '\r' || true)
  [[ "$user_state" == *RUNNING_UNLOCKED* ]] && break
  sleep 2
done
adb shell am get-started-user-state 0 >smoke-evidence/user-state.txt
grep -q RUNNING_UNLOCKED smoke-evidence/user-state.txt
adb shell input keyevent 82
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
if [[ "${KIZUKU_TEST_SIGNED_UPDATE:-false}" == true ]]; then
  scripts/signed-update-acceptance.sh "${1:-artifacts}"
fi
KIZUKU_EVIDENCE_DIR=smoke-evidence/shell scripts/emulator-smoke.sh "${1:-artifacts}"
if [[ "${KIZUKU_TEST_ROOT:-false}" == true ]]; then
  adb root
  timeout 30 adb wait-for-device
  test "$(adb shell id -u | tr -d '\r')" == 0
  KIZUKU_EVIDENCE_DIR=smoke-evidence/root scripts/emulator-smoke.sh "${1:-artifacts}"
fi
