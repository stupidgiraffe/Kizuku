#!/usr/bin/env bash
set -euo pipefail

[[ ${ANDROID_SERIAL:-} == emulator-* ]] || { echo 'Axeron WebUI fixture requires an emulator'; exit 1; }
evidence=${KIZUKU_EVIDENCE_DIR:?Set the emulator evidence directory}
temp=$(mktemp -d)
trap 'rm -rf "$temp"' EXIT
commit=1b08f1c9987ecf79b28c555fbcc7b2689147cf62
archive="$temp/axmanager.tar.gz"
fixture="$temp/axmanager-webui.zip"
curl --fail --silent --show-error --location --retry 3 --max-time 60 \
  "https://codeload.github.com/fahrez182/AxManager/tar.gz/$commit" -o "$archive"
printf '%s  %s\n' 45195304e5b993b05bd0e696a885a99a7541648298247e6698f29b03b71b4c1a "$archive" \
  | sha256sum -c | tee "$evidence/axmanager-source.txt"
python3 - "$archive" "$fixture" "$commit" <<'PY'
import sys
import tarfile
import zipfile

source, output, commit = sys.argv[1:]
# Stage the original page and scripts. This test only exercises the shipped WebUI.
with tarfile.open(source) as archive, zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as fixture:
    for path in ("module.prop", "webroot/index.html", "webroot/app.js", "webroot/style.css"):
        member = archive.getmember(f"AxManager-{commit}/plugins/test_function/{path}")
        if not member.isfile():
            raise RuntimeError(f"{path} is not a regular file")
        fixture.writestr(path, archive.extractfile(member).read())
PY
adb push "$fixture" /data/local/tmp/kizuku-axmanager-webui.zip
adb shell chmod 0644 /data/local/tmp/kizuku-axmanager-webui.zip
