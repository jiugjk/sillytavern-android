#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Static experimental APK verification. Does not certify WebView/Android behavior."""
import argparse
import hashlib
import json
import os
from pathlib import Path
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
    for required in ["package: name='dev.stshell.app'", "minSdkVersion:'34'", "targetSdkVersion:'36'", "native-code: 'arm64-v8a'", 'application-debuggable']:
        if required not in badging: raise ValueError(f'Unexpected APK policy: {required}')
    permissions = [line for line in badging.splitlines() if line.startswith('uses-permission:')]
    if permissions != ["uses-permission: name='android.permission.INTERNET'"]:
        raise ValueError('Unexpected permissions in foreground-only experiment')
    runtime = verify_artifacts(ROOT / 'build/runtime', lock, tc / 'bin/llvm-readelf')
    expected = contract()
    with zipfile.ZipFile(args.apk) as apk, tempfile.TemporaryDirectory() as temp:
        if json.loads(apk.read('assets/app-contract.json')) != expected: raise ValueError('Stale app contract/source inputs')
        if json.loads(apk.read('assets/runtime-manifest.json')) != runtime: raise ValueError('Wrong runtime manifest')
        for name, wanted in [('assets/payload/payload.zip', expected['payload']['archiveSha256']),
                             ('assets/payload/manifest.json', expected['payload']['manifestSha256']),
                             ('assets/app/entry.mjs', expected['inputs']['runtime/android/entry.mjs'])]:
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
    print(f'PASS: experimental APK static checks, {args.apk.stat().st_size} bytes. Android/WebView/background NOT verified.')


if __name__ == '__main__':
    try: main()
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        sys.exit(f'Experimental APK verification failed: {error}')
