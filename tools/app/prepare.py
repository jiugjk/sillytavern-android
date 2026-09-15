#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Publish the staged APK asset tree.

The staging directory is *fully managed*: every file the build packages is
declared here, and anything else under build/app-inputs/assets is removed. A
plain copy-if-changed pass would let a renamed or deleted source file survive
in an incremental build and ship inside the APK.
"""
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
from prepare_installer import current

# Emitted next to the assets so tools/app/verify.py can reject anything that is
# packaged but not declared by this script.
INVENTORY_NAME = 'managed-assets.json'


def copy_if_changed(source, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    if not destination.exists() or sha256(source) != sha256(destination):
        shutil.copy2(source, destination)


def publish(assets, planned, generated):
    """Copy planned files, write generated ones, then delete every stale entry."""
    managed = set(planned) | set(generated) | {INVENTORY_NAME}
    # The inventory names itself so a packaged tree can be compared exactly.
    generated = dict(generated)
    generated[INVENTORY_NAME] = json.dumps(
        {'schemaVersion': 1, 'assets': sorted(managed)}, indent=2) + '\n'
    for relative, source in sorted(planned.items()):
        copy_if_changed(source, assets / relative)
    for relative, text in sorted(generated.items()):
        target = assets / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.exists() or target.read_text() != text:
            target.write_text(text)
    existing = {p.relative_to(assets).as_posix() for p in assets.rglob('*') if p.is_file()}
    for stale in sorted(existing - managed):
        (assets / stale).unlink()
    # Drop directories emptied by the removals so the tree matches the plan.
    for directory in sorted((p for p in assets.rglob('*') if p.is_dir()),
                            key=lambda p: len(p.parts), reverse=True):
        if not any(directory.iterdir()):
            directory.rmdir()
    remaining = {p.relative_to(assets).as_posix() for p in assets.rglob('*') if p.is_file()}
    if remaining != managed:
        raise ValueError(f'Asset tree does not match the plan: {sorted(remaining ^ managed)}')
    return managed


def main():
    verify_project()
    subprocess.run([sys.executable, ROOT / 'tools/icons/verify.py'], check=True)
    lock = read_lock()
    _, tc, _ = sdk_paths(lock)
    verify_artifacts(ROOT / 'build/runtime', lock, tc / 'bin/llvm-readelf')
    try:
        artifact, pointer = current()
    except (ValueError, OSError):
        subprocess.run([sys.executable, ROOT / 'tools/app/prepare_installer.py'], check=True)
        artifact, pointer = current()
    assets = ROOT / 'build/app-inputs/assets'
    assets.mkdir(parents=True, exist_ok=True)

    planned = {
        'payload/payload.zip': artifact / 'payload.zip',
        'payload/manifest.json': artifact / 'manifest.json',
        'runtime-manifest.json': ROOT / 'build/runtime/manifest.json',
        'licenses/AGPL-3.0.txt': ROOT / 'LICENSE',
    }
    for file in (ROOT / 'runtime/android').iterdir():
        if file.is_file() and file.suffix in ('.mjs', '.js'):
            planned[f'app/{file.name}'] = file
    for file in (ROOT / 'licenses').iterdir():
        if file.is_file():
            planned[f'licenses/{file.name}'] = file

    data = contract()
    generated = {'app-contract.json': json.dumps(data, indent=2) + '\n'}
    managed = publish(assets, planned, generated)
    print(f'Prepared app inputs: {data["appIdentity"]} ({len(managed)} managed assets)')


if __name__ == '__main__':
    try: main()
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        sys.exit(f'App preparation failed: {error}')
