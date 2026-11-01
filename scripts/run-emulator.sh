#!/usr/bin/env bash
set -euo pipefail
mkdir -p smoke-evidence
printf 'no\n' | avdmanager create avd --force -n kizuku-ci --package 'system-images;android-37.0;google_apis;x86_64' --device pixel_7
task_avd_dir="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
printf 'disk.dataPartition.size=6G\n' >>"$task_avd_dir/kizuku-ci.avd/config.ini"
"$ANDROID_HOME/emulator/emulator" -avd kizuku-ci -no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim -camera-back none -memory 2048 -partition-size 6144 >smoke-evidence/emulator.log 2>&1 &
emulator_pid=$!
cleanup() {
  set +e
  timeout 10 adb logcat -d -b crash -b system >smoke-evidence/framework.log
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
adb shell input keyevent 82
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
scripts/emulator-smoke.sh "${1:-artifacts}"
