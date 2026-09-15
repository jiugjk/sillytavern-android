#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Verify APK identity, permissions, native libraries and packaged assets.

The same content checks run for every variant. Only the debuggable flag and the
signature requirement differ, so a release APK can no longer reach Releases on
"signed and aligned" alone.
"""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import zipfile
from app_inputs import ROOT, contract
from runtime_common import capture, inspect_elf, read_lock, run, sdk_paths, sha256, verify_artifacts

# tools/app/prepare.py writes this inventory next to the assets it manages.
INVENTORY_NAME = 'managed-assets.json'


def digest(stream):
    h = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b''): h.update(block)
    return h.hexdigest()


def resource_files(dump):
    """Resolve file resources by logical identity, including aapt2 shortened paths."""
    resources, current = {}, None
    for line in dump.splitlines():
        match = re.match(r'\s*resource 0x[0-9a-fA-F]+ ([\w/]+)(?:\s|$)', line)
        if match:
            current = match.group(1)
            resources.setdefault(current, [])
        else:
            match = re.match(r'\s*\(([^)]*)\) \(file\) (res/\S+) type=', line)
            if match and current:
                resources[current].append((match.group(1), match.group(2)))
    return resources


def resource_path(resources, name, qualifier):
    paths = [p for config, p in resources.get(name, [])
             if (qualifier in config.split('-') if qualifier else config == '')]
    if len(paths) != 1:
        raise ValueError(f'Expected one APK resource {name} ({qualifier}), got {paths}')
    return paths[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--variant', choices=['debug', 'release'], required=True,
                        help='expected build variant: debug must be debuggable, release must not be')
    parser.add_argument('--require-signature', action='store_true',
                        help='fail when the APK is not signed (always use this for a published APK)')
    args = parser.parse_args()
    lock = read_lock(); _, tc, _ = sdk_paths(lock)
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
    tools = sdk / 'build-tools' / lock['android']['buildToolsVersion']
    try:
        run([tools / 'apksigner', 'verify', '--verbose', args.apk])
        signed = True
    except subprocess.CalledProcessError:
        if args.require_signature:
            raise ValueError('APK signature verification failed and a signature is required')
        signed = False
        print('WARN: APK is unsigned; content checks continue but it cannot be installed or published.')
    run([tools / 'zipalign', '-c', '-P', '16', '-v', '4', args.apk], stdout=subprocess.DEVNULL)
    badging = capture([tools / 'aapt2', 'dump', 'badging', args.apk])
    for required in ["package: name='dev.stshell.app'", "minSdkVersion:'34'", "targetSdkVersion:'36'", "native-code: 'arm64-v8a'", "application: label='SillyTavern'"]:
        if required not in badging: raise ValueError(f'Unexpected APK policy: {required}')
    debuggable = 'application-debuggable' in badging
    if args.variant == 'debug' and not debuggable:
        raise ValueError('Debug APK is expected to carry application-debuggable')
    if args.variant == 'release' and debuggable:
        raise ValueError('Release APK must NOT be debuggable')
    permissions = {line.split("name='")[1].split("'")[0] for line in badging.splitlines() if line.startswith('uses-permission:')}
    expected_permissions = {'android.permission.INTERNET', 'android.permission.POST_NOTIFICATIONS',
                            'android.permission.FOREGROUND_SERVICE', 'android.permission.FOREGROUND_SERVICE_SPECIAL_USE',
                            'android.permission.WAKE_LOCK'}
    if permissions != expected_permissions:
        raise ValueError(f'Unexpected launcher permissions: {sorted(permissions)}')
    runtime = verify_artifacts(ROOT / 'build/runtime', lock, tc / 'bin/llvm-readelf')
    expected = contract()
    with zipfile.ZipFile(args.apk) as apk, tempfile.TemporaryDirectory() as temp:
        resources = resource_files(capture([tools / 'aapt2', 'dump', 'resources', args.apk]))
        icon_metadata = json.loads((ROOT / 'tools/icons/manifest.json').read_text())
        for density, size in icon_metadata['densities'].items():
            for name in ('ic_launcher', 'ic_launcher_round'):
                data = apk.read(resource_path(resources, f'mipmap/{name}', density))
                if not data.startswith(b'\x89PNG\r\n\x1a\n') or struct.unpack('>II', data[16:24]) != (size, size):
                    raise ValueError(f'Packaged icon dimensions mismatch: {density}/{name}')
        for name in ('ic_launcher', 'ic_launcher_round'):
            tree = capture([tools / 'aapt2', 'dump', 'xmltree', args.apk, '--file', resource_path(resources, f'mipmap/{name}', 'anydpi')])
            if not all(f'E: {tag}' in tree for tag in ('adaptive-icon', 'background', 'foreground', 'monochrome')):
                raise ValueError(f'Incomplete adaptive icon: {name}')
        if resource_path(resources, 'drawable/ic_stat_sillytavern', '') not in apk.namelist():
            raise ValueError('Missing notification icon')
        if json.loads(apk.read('assets/app-contract.json')) != expected: raise ValueError('Stale app contract/source inputs')
        if json.loads(apk.read('assets/runtime-manifest.json')) != runtime: raise ValueError('Wrong runtime manifest')
        # Reject anything packaged under assets/ that prepare.py did not declare,
        # so a stale file from an incremental build cannot ride along unnoticed.
        inventory = json.loads(apk.read(f'assets/{INVENTORY_NAME}'))
        if inventory.get('schemaVersion') != 1:
            raise ValueError('Unknown managed-asset inventory schema')
        declared = set(inventory['assets'])
        prepared_inventory = json.loads((ROOT / 'build/app-inputs/assets' / INVENTORY_NAME).read_text())
        if inventory != prepared_inventory:
            raise ValueError('APK managed inventory differs from the prepared build inputs')
        packaged = {name[len('assets/'):] for name in apk.namelist() if name.startswith('assets/') and not name.endswith('/')}
        # AGP generates these compiled profiles only during release packaging;
        # they are not source assets and their final bytes do not exist at prepare.
        # Permit only this exact pair, with the expected binary magic and bounds.
        profiles = {'dexopt/baseline.prof': b'pro\x00', 'dexopt/baseline.profm': b'prm\x00'}
        generated = packaged & profiles.keys()
        if generated:
            if args.variant != 'release' or generated != profiles.keys() or generated & declared:
                raise ValueError('Unexpected Android-generated asset inventory')
            for name in generated:
                entry = apk.getinfo('assets/' + name)
                if not 8 <= entry.file_size <= 16 * 1024 * 1024 or not apk.read(entry).startswith(profiles[name]):
                    raise ValueError('Invalid generated baseline profile')
        if packaged - generated != declared:
            raise ValueError(f'Packaged assets differ from the managed inventory: {sorted((packaged - generated) ^ declared)}')
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
    signature = 'signed' if signed else 'UNSIGNED'
    print(f'PASS: APK static checks ({args.variant}, {signature}), {args.apk.stat().st_size} bytes.')


if __name__ == '__main__':
    try: main()
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        sys.exit(f'APK verification failed: {error}')
