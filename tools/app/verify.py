#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Verify APK identity, permissions, native libraries and packaged assets."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import zipfile
from app_inputs import ROOT, contract
from runtime_common import capture, inspect_elf, read_lock, run, sdk_paths, sha256, verify_artifacts


def digest(stream):
    h = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b''): h.update(block)
    return h.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    args = parser.parse_args()
    lock = read_lock(); _, tc, _ = sdk_paths(lock)
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
    tools = sdk / 'build-tools' / lock['android']['buildToolsVersion']
    run([tools / 'apksigner', 'verify', '--verbose', args.apk])
    run([tools / 'zipalign', '-c', '-P', '16', '-v', '4', args.apk], stdout=subprocess.DEVNULL)
    badging = capture([tools / 'aapt2', 'dump', 'badging', args.apk])
    for required in ["package: name='dev.stshell.app'", "minSdkVersion:'34'", "targetSdkVersion:'36'", "native-code: 'arm64-v8a'", "application: label='SillyTavern'", 'application-debuggable']:
        if required not in badging: raise ValueError(f'Unexpected APK policy: {required}')
    permissions = {line.split("name='")[1].split("'")[0] for line in badging.splitlines() if line.startswith('uses-permission:')}
    expected_permissions = {'android.permission.INTERNET', 'android.permission.POST_NOTIFICATIONS',
                            'android.permission.FOREGROUND_SERVICE', 'android.permission.FOREGROUND_SERVICE_SPECIAL_USE',
                            'android.permission.WAKE_LOCK'}
    if permissions != expected_permissions:
        raise ValueError(f'Unexpected launcher permissions: {sorted(permissions)}')
    runtime = verify_artifacts(ROOT / 'build/runtime', lock, tc / 'bin/llvm-readelf')
    expected = contract()
    with zipfile.ZipFile(args.apk) as apk, tempfile.TemporaryDirectory() as temp:
        icon_metadata = json.loads((ROOT / 'tools/icons/manifest.json').read_text())
        for density, size in icon_metadata['densities'].items():
            for name in ('ic_launcher', 'ic_launcher_round'):
                data = apk.read(f'res/mipmap-{density}-v4/{name}.png')
                if not data.startswith(b'\x89PNG\r\n\x1a\n') or struct.unpack('>II', data[16:24]) != (size, size):
                    raise ValueError(f'Packaged icon dimensions mismatch: {density}/{name}')
        for name in ('ic_launcher', 'ic_launcher_round'):
            tree = capture([tools / 'aapt2', 'dump', 'xmltree', args.apk, '--file', f'res/mipmap-anydpi-v33/{name}.xml'])
            if not all(f'E: {tag}' in tree for tag in ('adaptive-icon', 'background', 'foreground', 'monochrome')):
                raise ValueError(f'Incomplete adaptive icon: {name}')
        if 'res/drawable/ic_stat_sillytavern.xml' not in apk.namelist():
            raise ValueError('Missing notification icon')
        if json.loads(apk.read('assets/app-contract.json')) != expected: raise ValueError('Stale app contract/source inputs')
        if json.loads(apk.read('assets/runtime-manifest.json')) != runtime: raise ValueError('Wrong runtime manifest')
        for name, wanted in [('assets/payload/payload.zip', expected['payload']['archiveSha256']),
                             ('assets/payload/manifest.json', expected['payload']['manifestSha256']),
                             ('assets/app/entry.mjs', expected['inputs']['runtime/android/entry.mjs']),
                             ('assets/app/request-observer.mjs', expected['inputs']['runtime/android/request-observer.mjs']),
                             ('assets/app/generation-observer.js', expected['inputs']['runtime/android/generation-observer.js'])]:
            with apk.open(name) as stream:
                if digest(stream) != wanted: raise ValueError(f'Packaged asset checksum mismatch: {name}')
        if apk.getinfo('assets/payload/payload.zip').compress_type != zipfile.ZIP_STORED:
            raise ValueError('Payload ZIP should not be redundantly compressed')
        if apk.read('assets/licenses/AGPL-3.0.txt') != (ROOT / 'LICENSE').read_bytes(): raise ValueError('Missing AGPL license')
        libs = [x for x in apk.infolist() if x.filename.startswith('lib/') and x.filename.endswith('.so')]
        if {x.filename for x in libs} != {f'lib/arm64-v8a/{n}' for n in ('libnode.so', 'libstprobe.so', 'libc++_shared.so')}:
            raise ValueError('Unexpected native inventory')
        for item in libs:
            if item.compress_type != zipfile.ZIP_STORED: raise ValueError('Native library must be stored uncompressed')
            target = Path(temp) / Path(item.filename).name
            with apk.open(item) as stream, target.open('wb') as out:
                for block in iter(lambda: stream.read(1024 * 1024), b''): out.write(block)
            inspect_elf(target, tc / 'bin/llvm-readelf', extra_dependencies={'libnode.so'})
            if target.name == 'libnode.so' and sha256(target) != runtime['files']['lib/libnode.so']: raise ValueError('Changed libnode')
    print(f'PASS: APK static checks, {args.apk.stat().st_size} bytes.')


if __name__ == '__main__':
    try: main()
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        sys.exit(f'APK verification failed: {error}')
