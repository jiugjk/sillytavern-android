#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Build only the APK-pinned installer/tooling. ST and extensions are NOT bundled."""
import base64
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tarfile
import tempfile
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/payload'))
from common import canonical, inventory, sha256, verify_zip
import importlib.util
_spec = importlib.util.spec_from_file_location('payload_prepare', ROOT / 'tools/payload/prepare.py')
if _spec is None or _spec.loader is None:
    raise ImportError('Cannot load payload ZIP writer')
_payload_prepare = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_payload_prepare)
write_zip = _payload_prepare.write_zip

REQUIRED = ['shell/first-install.mjs', 'shell/bootstrap.mjs', 'shell/policy.mjs',
            'shell/mobile-policy.json', 'shell/first-install.json', 'shell/npm/package.json']
POLICY = {'maxEntries': 60000, 'maxFileBytes': 134217728, 'maxTotalBytes': 1073741824,
          'requiredFiles': REQUIRED}


def runtime_recipe():
    paths = [ROOT / 'config/first-install.json', ROOT / 'config/mobile-policy.json',
             *sorted((ROOT / 'runtime/mobile').glob('*.mjs')),
             ROOT / 'tools/app/prepare_installer.py']
    return {p.relative_to(ROOT).as_posix(): sha256(p) for p in paths}


def audit_recipe():
    paths = [ROOT / 'config/first-install.json', ROOT / 'config/mobile-policy.json',
             *sorted((ROOT / 'runtime/mobile').glob('*.mjs')),
             *sorted((ROOT / 'tools/app').glob('*.py')),
             ROOT / 'tools/payload/common.py', ROOT / 'tools/payload/prepare.py']
    return {p.relative_to(ROOT).as_posix(): sha256(p) for p in paths}


def recipe():
    return runtime_recipe()


def current():
    base = ROOT / 'build/installer'
    pointer = json.loads((base / 'current.json').read_text())
    if len(pointer['payloadId']) != 64 or any(c not in '0123456789abcdef' for c in pointer['payloadId']):
        raise ValueError('Invalid installer identity')
    artifact = base / pointer['payloadId']
    if sha256(artifact / 'manifest.json') != pointer['manifestSha256'] or sha256(artifact / 'payload.zip') != pointer['archiveSha256']:
        raise ValueError('Installer artifact hash mismatch')
    manifest = json.loads((artifact / 'manifest.json').read_text())
    if manifest.get('kind') != 'online-installer' or manifest.get('recipe') != recipe():
        raise ValueError('Stale installer; run tools/app/prepare_installer.py')
    verify_zip(artifact / 'payload.zip', manifest, policy=POLICY)
    return artifact, pointer


def main():
    build = ROOT / 'build'
    build.mkdir(exist_ok=True)
    config = json.loads((ROOT / 'config/first-install.json').read_text())
    with tempfile.TemporaryDirectory(prefix='installer-build-', dir=build) as tmp:
        work = Path(tmp)
        archive = work / 'npm.tgz'
        with urllib.request.urlopen(config['npm']['url'], timeout=90) as response:
            data = response.read(32 * 1024 * 1024 + 1)
        if len(data) > 32 * 1024 * 1024:
            raise ValueError('npm archive too large')
        sri = 'sha512-' + base64.b64encode(hashlib.sha512(data).digest()).decode()
        if sri != config['npm']['integrity']:
            raise ValueError('npm archive integrity mismatch')
        archive.write_bytes(data)
        tree = work / 'tree'
        shell = tree / 'shell'
        shell.mkdir(parents=True)
        with tarfile.open(archive) as package:
            for entry in package.getmembers():
                parts = Path(entry.name).parts
                if not parts or parts[0] != 'package' or '..' in parts or entry.name.startswith('/') or not (entry.isfile() or entry.isdir()):
                    raise ValueError(f'Unsafe npm member: {entry.name}')
            package.extractall(work / 'npm', filter='data')
        shutil.move(work / 'npm/package', shell / 'npm')
        for p in (ROOT / 'runtime/mobile').glob('*.mjs'):
            shutil.copy2(p, shell / p.name)
        for name in ('mobile-policy.json', 'first-install.json'):
            shutil.copy2(ROOT / 'config' / name, shell / name)
        files, dirs, total, wasm = inventory(tree, POLICY)
        content = {'schemaVersion': 1, 'kind': 'online-installer', 'recipe': recipe(),
                   'auditRecipe': audit_recipe(),
                   'files': files, 'directories': dirs, 'totalBytes': total, 'wasmFiles': wasm}
        manifest = {**content, 'payloadId': hashlib.sha256(canonical(content)).hexdigest()}
        output = ROOT / 'build/installer' / manifest['payloadId']
        output.parent.mkdir(parents=True, exist_ok=True)

        # If an identical, valid published artifact already exists, reuse it without rebuilding/truncating
        if output.exists() and (output / 'manifest.json').is_file() and (output / 'payload.zip').is_file():
            if sha256(output / 'manifest.json') == hashlib.sha256(canonical(manifest) + b'\n').hexdigest():
                try:
                    verify_zip(output / 'payload.zip', manifest, policy=POLICY)
                    pointer = {'schemaVersion': 1, 'kind': 'online-installer', 'payloadId': manifest['payloadId'],
                               'manifestSha256': sha256(output / 'manifest.json'), 'archiveSha256': sha256(output / 'payload.zip'),
                               'archiveBytes': (output / 'payload.zip').stat().st_size,
                               'uncompressedBytes': total, 'fileCount': len(files)}
                    temporary = output.parent / f'.current-{uuid.uuid4().hex}.tmp'
                    temporary.write_text(json.dumps(pointer, indent=2) + '\n')
                    temporary.replace(output.parent / 'current.json')
                    print(json.dumps(pointer, indent=2))
                    return
                except Exception:
                    pass

        # Build and verify in a unique temporary staging directory, then atomically publish
        stage = output.parent / f'.stage-{uuid.uuid4().hex}'
        stage.mkdir(parents=True, exist_ok=True)
        try:
            (stage / 'manifest.json').write_bytes(canonical(manifest) + b'\n')
            write_zip(tree, files, dirs, stage / 'payload.zip', 6)
            verify_zip(stage / 'payload.zip', manifest, policy=POLICY)
            if output.exists():
                shutil.rmtree(output)
            stage.replace(output)
        finally:
            if stage.exists():
                shutil.rmtree(stage, ignore_errors=True)

        pointer = {'schemaVersion': 1, 'kind': 'online-installer', 'payloadId': manifest['payloadId'],
                   'manifestSha256': sha256(output / 'manifest.json'), 'archiveSha256': sha256(output / 'payload.zip'),
                   'archiveBytes': (output / 'payload.zip').stat().st_size,
                   'uncompressedBytes': total, 'fileCount': len(files)}
        temporary = output.parent / f'.current-{uuid.uuid4().hex}.tmp'
        temporary.write_text(json.dumps(pointer, indent=2) + '\n')
        temporary.replace(output.parent / 'current.json')
        print(json.dumps(pointer, indent=2))


if __name__ == '__main__':
    main()
