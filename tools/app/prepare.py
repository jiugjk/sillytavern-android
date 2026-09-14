#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
import json
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile
from app_inputs import ROOT, contract
from runtime_common import read_lock, sdk_paths, sha256, verify_artifacts
from verify_project import main as verify_project
sys.path.insert(0, str(ROOT / 'tools/payload'))
from common import verify_zip
from verify import current_artifact, verify_source


def copy_if_changed(source, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    if not destination.exists() or sha256(source) != sha256(destination):
        shutil.copy2(source, destination)


def main():
    verify_project()
    subprocess.run([sys.executable, ROOT / 'tools/icons/verify.py'], check=True)
    lock = read_lock()
    _, tc, _ = sdk_paths(lock)
    verify_artifacts(ROOT / 'build/runtime', lock, tc / 'bin/llvm-readelf')
    artifact, pointer = current_artifact()
    if sha256(artifact / 'payload.zip') != pointer['archiveSha256'] or sha256(artifact / 'manifest.json') != pointer['manifestSha256']:
        raise ValueError('Payload artifact mismatch; rebuild/review it first')
    manifest = json.loads((artifact / 'manifest.json').read_text())
    verify_source(manifest)
    verify_zip(artifact / 'payload.zip', manifest)
    assets = ROOT / 'build/app-inputs/assets'
    copy_if_changed(artifact / 'payload.zip', assets / 'payload/payload.zip')
    copy_if_changed(artifact / 'manifest.json', assets / 'payload/manifest.json')
    for file in (ROOT / 'runtime/android').iterdir():
        if file.is_file() and file.suffix in ('.mjs', '.js'):
            copy_if_changed(file, assets / 'app' / file.name)
    copy_if_changed(ROOT / 'build/runtime/manifest.json', assets / 'runtime-manifest.json')
    copy_if_changed(ROOT / 'LICENSE', assets / 'licenses/AGPL-3.0.txt')
    for file in (ROOT / 'licenses').iterdir():
        if file.is_file(): copy_if_changed(file, assets / 'licenses' / file.name)
    data = contract()
    (assets / 'app-contract.json').write_text(json.dumps(data, indent=2) + '\n')
    print('Prepared app inputs:', data['appIdentity'])


if __name__ == '__main__':
    try: main()
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        sys.exit(f'App preparation failed: {error}')
