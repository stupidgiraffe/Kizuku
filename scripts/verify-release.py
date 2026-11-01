#!/usr/bin/env python3
"""Check the actual APK signatures, build flags and bundled compatibility payload."""
from pathlib import Path
import os
import subprocess
import zipfile

fingerprint = '83c9b70c57b2666f8eb48f0cfe74013053c804fd0b452addfdeed451629d2bce'
build_tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/37.0.0'
packages = {}
version = dict(line.split('=', 1) for line in Path('release.properties').read_text().splitlines() if '=' in line)
for module in ('manager', 'compat', 'test-client'):
    apk = Path(f'{module}/build/outputs/apk/release/{module}-release.apk')
    signature = subprocess.check_output([build_tools/'apksigner', 'verify', '--verbose', '--print-certs', apk], text=True)
    if f'certificate SHA-256 digest: {fingerprint}' not in signature:
        raise SystemExit(f'{module}: permanent signing identity mismatch')
    manifest = subprocess.check_output([build_tools/'aapt2', 'dump', 'badging', apk], text=True)
    if f"versionCode='{version['versionCode']}'" not in manifest or f"versionName='{version['versionName']}'" not in manifest:
        raise SystemExit(f'{module}: release version mismatch')
    if 'application-debuggable' in manifest:
        raise SystemExit(f'{module}: release is debuggable')
    packages[module] = apk
with zipfile.ZipFile(packages['manager']) as archive:
    if archive.read('assets/legacy-compat.apk') != packages['compat'].read_bytes():
        raise SystemExit('Bundled compatibility APK differs from signed build')
    for notice in ('LICENSE', 'NOTICE', 'BORINGSSL-LICENSE.txt', 'BOUNCYCASTLE-LICENSE.txt'):
        if archive.read('assets/'+notice) != Path(notice).read_bytes():
            raise SystemExit(f'{notice} is missing or stale')

    for abi in ('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'):
        if f'lib/{abi}/libkizuku_adb.so' not in archive.namelist():
            raise SystemExit(f'Missing ADB pairing library for {abi}')
print('Permanent signatures, version, non-debuggable manifests, pairing ABIs, companion and notices verified')
