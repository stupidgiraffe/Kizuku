#!/usr/bin/env bash
set -euo pipefail
[[ ${ANDROID_SERIAL:-} == emulator-* ]] || { echo 'Axeron plugin fixture requires an emulator'; exit 1; }
evidence=${KIZUKU_EVIDENCE_DIR:?Set the emulator evidence directory}
temp=$(mktemp -d)
trap 'rm -rf "$temp"' EXIT
fixture="$temp/test_function-v2.0.0.zip"
curl --fail --silent --show-error --location --retry 3 --max-time 90 \
  https://github.com/fahrez182/AxManager/releases/download/v3.0.0/test_function-v2.0.0.zip \
  --output "$fixture"
printf '%s  %s\n' b5c6328f17800c91bce2440de5cd947f25142bbb9ec85264d310517613e10e9f "$fixture" \
  | sha256sum -c | tee "$evidence/axmanager-original-plugin.txt"
adb push "$fixture" /data/local/tmp/kizuku-axmanager-webui.zip
adb shell chmod 0644 /data/local/tmp/kizuku-axmanager-webui.zip
