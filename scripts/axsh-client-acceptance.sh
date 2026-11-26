#!/usr/bin/env bash
set -euo pipefail
[[ ${ANDROID_SERIAL:-} == emulator-* ]] || { echo 'Axsh reference acceptance requires an emulator'; exit 1; }
evidence=${KIZUKU_EVIDENCE_DIR:?Set the emulator evidence directory}
mkdir -p "$evidence"
temporary=$(mktemp -d)
trap 'rm -rf "$temporary"' EXIT
apk="$temporary/demo.apk"
curl --fail --silent --show-error --location --retry 3 --max-time 90 \
  https://github.com/fahrez182/AxManager/releases/download/v3.0.0/DemoAxsh_v2.0.0_30000-release_2610291728.apk \
  --output "$apk"
printf '%s  %s\n' 17219acd16c8ba56ab7801a6c082c43a0678368ba94c0f2eebd0b01b01d0eecd "$apk" \
  | sha256sum -c | tee "$evidence/original-axsh-client.txt"
adb install -r "$apk"
