#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Verify a payload against the official checkout; optionally unpack for diagnostics."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import zipfile

from common import ROOT, read_lock, recipe_inputs, sha256, unpack, validate_manifest, verify_zip
from verify_project import main as verify_project


def verify_source(manifest):
    lock = read_lock()
    for key, value in lock['sillytavern'].items():
        if manifest['upstream'].get(key) != value:
            raise ValueError(f'Payload upstream identity drift: {key}')
    source = ROOT / 'upstream/SillyTavern'
    names = subprocess.check_output(['git', '-C', str(source), 'ls-tree', '-rz', '--name-only', lock['sillytavern']['commit']]).decode().split('\0')
    wanted = {f'server/{name}' for name in names if name}
    actual = {name for name in manifest['files'] if name.startswith('server/') and not name.startswith('server/node_modules/')}
    if wanted != actual:
        raise ValueError('Payload source file inventory is not the official tracked tree')
    for name in wanted:
        if sha256(source / name.removeprefix('server/')) != manifest['files'][name]['sha256']:
            raise ValueError(f'Payload changed an upstream source file: {name}')
    adapters = {
        'shell/bootstrap.mjs': ROOT / 'runtime/mobile/bootstrap.mjs',
        'shell/policy.mjs': ROOT / 'runtime/mobile/policy.mjs',
        'shell/mobile-policy.json': ROOT / 'config/mobile-policy.json',
    }
    for name, path in adapters.items():
        if sha256(path) != manifest['files'][name]['sha256']:
            raise ValueError(f'Payload has an outdated adapter/policy: {name}; rebuild')
    if manifest['recipe'] != recipe_inputs():
        raise ValueError('Payload recipe changed; rebuild rather than accepting an outdated bundle')


def current_artifact():
    pointer = json.loads((ROOT / 'build/payload/current.json').read_text())
    if not re.fullmatch('[a-f0-9]{64}', pointer['payloadId']):
        raise ValueError('Invalid current payload pointer')
    return ROOT / 'build/payload' / pointer['payloadId'], pointer


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--artifact', type=Path)
    parser.add_argument('--expected-manifest', help='Required when choosing an explicit artifact')
    parser.add_argument('--unpack', type=Path, help='New directory, never a live existing installation')
    args = parser.parse_args()
    verify_project()
    if args.artifact:
        if not args.expected_manifest:
            parser.error('--artifact requires --expected-manifest from trusted build metadata')
        artifact, expected = args.artifact.resolve(), args.expected_manifest
    else:
        artifact, pointer = current_artifact()
        expected = pointer['manifestSha256']
        if sha256(artifact / 'payload.zip') != pointer['archiveSha256']:
            raise ValueError('Archive checksum differs from current build pointer')
    if (artifact / 'manifest.json').stat().st_size > 32 * 1024 * 1024:
        raise ValueError('Manifest is too large')
    if sha256(artifact / 'manifest.json') != expected:
        raise ValueError('Manifest checksum mismatch')
    manifest = validate_manifest(json.loads((artifact / 'manifest.json').read_text()))
    verify_source(manifest)
    if args.unpack:
        unpack(artifact, args.unpack, expected)
    else:
        verify_zip(artifact / 'payload.zip', manifest)
    print(f'PASS: {manifest["payloadId"]}, {len(manifest["files"])} files, unmodified official ST; Android NOT tested')
    if args.unpack:
        print('Launch descriptor fields:', json.dumps({
            'schemaVersion': 1, 'payloadRoot': str(args.unpack.resolve()),
            'stateRoot': '<absolute dedicated private state directory>',
            'manifestSha256': expected, 'port': '<installation-persistent integer port>',
        }))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        sys.exit(f'Payload verification failed: {error}')
