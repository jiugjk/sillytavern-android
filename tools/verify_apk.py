#!/usr/bin/env python3
"""Static checks of the actual probe APK, not a claim of device execution."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile

from runtime_common import ROOT, capture, identity, inspect_elf, probe_manifest, read_lock, run, sdk_paths, sha256, verify_artifacts


def check_badging(badging):
    patterns = [r"^package: name='dev\.stshell\.probe' ",
                r"^(?:minSdkVersion|sdkVersion):'34'$",
                r"^targetSdkVersion:'36'$", r"^native-code: 'arm64-v8a'$",
                r"^application-debuggable$"]
    if not all(re.search(pattern, badging, re.MULTILINE) for pattern in patterns):
        raise ValueError('APK must be the debuggable probe, Android14+/target36, arm64 only')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    args = parser.parse_args()
    lock = read_lock()
    _, tc, _ = sdk_paths(lock)
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
    build_tools = sdk / 'build-tools' / lock['android']['buildToolsVersion']
    run([build_tools / 'apksigner', 'verify', '--verbose', args.apk])
    run([build_tools / 'zipalign', '-c', '-P', '16', '-v', '4', args.apk], stdout=subprocess.DEVNULL)
    badging = capture([build_tools / 'aapt2', 'dump', 'badging', args.apk])
    check_badging(badging)
    artifact_manifest = verify_artifacts(ROOT / 'build/runtime', lock, tc / 'bin/llvm-readelf')
    with zipfile.ZipFile(args.apk) as package, tempfile.TemporaryDirectory() as tmp:
        if package.read('assets/licenses/AGPL-3.0.txt') != (ROOT / 'LICENSE').read_bytes():
            raise ValueError('APK is missing the correct AGPL license')
        if package.read('assets/licenses/runtime/Node.txt') != (ROOT / 'build/runtime/licenses/Node.txt').read_bytes():
            raise ValueError('APK is missing the matching Node license/third-party notices')
        if json.loads(package.read('assets/probe-manifest.json')) != probe_manifest():
            raise ValueError('APK contains an outdated/different probe implementation')
        manifest = json.loads(package.read('assets/runtime-manifest.json'))
        if manifest != artifact_manifest or manifest['buildIdentity'] != identity(lock):
            raise ValueError('APK carries the wrong runtime manifest')
        libraries = [entry for entry in package.infolist() if entry.filename.startswith('lib/') and entry.filename.endswith('.so')]
        names = {entry.filename for entry in libraries}
        expected = {'lib/arm64-v8a/libnode.so', 'lib/arm64-v8a/libstprobe.so', 'lib/arm64-v8a/libc++_shared.so'}
        if names != expected:
            raise ValueError(f'Unexpected APK native inventory: {sorted(names)}')
        for entry in libraries:
            if entry.compress_type != zipfile.ZIP_STORED:
                raise ValueError('Native libraries should be stored uncompressed for direct APK loading')
            path = Path(tmp) / Path(entry.filename).name
            path.write_bytes(package.read(entry))
            inspect_elf(path, tc / 'bin/llvm-readelf', extra_dependencies={'libnode.so'})
            if path.name == 'libnode.so' and sha256(path) != manifest['files']['lib/libnode.so']:
                raise ValueError('Packaged libnode differs from the verified build')
    print(f'PASS: {args.apk} ({args.apk.stat().st_size} bytes), signatures/ABI/ELF/ZIP/runtime identity; device execution NOT checked')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        sys.exit(f'APK verification failed: {error}')
