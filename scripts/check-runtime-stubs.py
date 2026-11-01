#!/usr/bin/env python3
"""Reject compile-only Android *Hidden shim descriptors in the shipped DEX."""
import re
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as apk:
    references = set()
    for name in apk.namelist():
        if re.fullmatch(r"classes\d*\.dex", name):
            references.update(re.findall(rb"Landroid/[A-Za-z0-9_/$]*Hidden;", apk.read(name)))
if references:
    raise SystemExit("Compile-only hidden API stubs remain in APK: " + ", ".join(sorted(item.decode() for item in references)))
print("Runtime hidden-API descriptor check passed")
