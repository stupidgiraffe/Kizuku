#!/usr/bin/env python3
"""Check the actual APK signatures, build flags and bundled compatibility payload."""
from pathlib import Path
import os
import subprocess
import zipfile

fingerprint = '83c9b70c57b2666f8eb48f0cfe74013053c804fd0b452addfdeed451629d2bce'
build_tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/37.0.0'
packages = {}
for module in ('manager', 'compat', 'test-client'):
    apk = Path(f'{module}/build/outputs/apk/release/{module}-release.apk')
    signature = subprocess.check_output([build_tools/'apksigner', 'verify', '--verbose', '--print-certs', apk], text=True)
    if f'certificate SHA-256 digest: {fingerprint}' not in signature:
        raise SystemExit(f'{module}: permanent signing identity mismatch')
    manifest = subprocess.check_output([build_tools/'aapt2', 'dump', 'badging', apk], text=True)
    if 'application-debuggable' in manifest:
        raise SystemExit(f'{module}: release is debuggable')
    packages[module] = apk
with zipfile.ZipFile(packages['manager']) as archive:
    if archive.read('assets/legacy-compat.apk') != packages['compat'].read_bytes():
        raise SystemExit('Bundled compatibility APK differs from signed build')
    for notice in ('LICENSE', 'NOTICE'):
        if archive.read('assets/'+notice) != Path(notice).read_bytes():
            raise SystemExit(f'{notice} is missing or stale')
print('Permanent signatures, non-debuggable manifests, companion and notices verified')
