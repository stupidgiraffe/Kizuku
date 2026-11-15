#!/usr/bin/env bash
set -euo pipefail

[[ ${ANDROID_SERIAL:-} == emulator-* ]] || { echo 'Module fixture requires an emulator'; exit 1; }
evidence=${KIZUKU_EVIDENCE_DIR:?Set the emulator evidence directory}
temp=$(mktemp -d)
trap 'rm -rf "$temp"' EXIT
commit=949fe5351787b30d24c91db66aa0c89290883a29
archive="$temp/sysinfo.tar.gz"
fixture="$temp/sysinfo.zip"
curl --fail --silent --show-error --location --retry 3 --max-time 60 \
  "https://codeload.github.com/HaydarReiss31/shevery-modules/tar.gz/$commit" -o "$archive"
printf '%s  %s\n' 05a6bea13a3ca0af9a06b7a84f8ed412f8608482d9c7d721da52fd5a14d68f66 "$archive" \
  | sha256sum -c | tee "$evidence/shevery-module-source.txt"
# Preserve the exact upstream module payload. The ZIP contains no other files.
python3 - "$archive" "$fixture" "$commit" <<'PY'
import sys
import tarfile
import zipfile

archive_path, zip_path, commit = sys.argv[1:]
with tarfile.open(archive_path) as archive, zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as fixture:
    for path in ("module.prop", "action.sh", "banner.png", "webui/index.html"):
        source = archive.getmember(f"shevery-modules-{commit}/modules/sysinfo/{path}")
        if not source.isfile():
            raise RuntimeError(f"{path} is not a regular file")
        fixture.writestr(path, archive.extractfile(source).read())
PY
adb push "$fixture" /data/local/tmp/kizuku-sysinfo.zip
adb shell chmod 0644 /data/local/tmp/kizuku-sysinfo.zip
