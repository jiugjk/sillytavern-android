#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Prepare an unmodified ST + locked production-dependency payload, not an APK."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import zipfile
import zlib

from common import ROOT, canonical, check_platform_selection, inventory, read_lock, read_policy, recipe_inputs, safe_name, sha256, verify_zip, run, capture
from verify_project import main as verify_project


def dependencies(server):
    original = json.loads((server / 'package-lock.json').read_text())['packages']
    installed = json.loads((server / 'node_modules/.package-lock.json').read_text())['packages']
    packages = []
    for name, entry in sorted(installed.items()):
        safe_name(f'server/{name}')
        if name not in original or entry.get('integrity') != original[name].get('integrity'):
            raise ValueError(f'Installed dependency does not match upstream lock: {name}')
        directory = server / name
        package = json.loads((directory / 'package.json').read_text())
        if package['version'] != original[name]['version']:
            raise ValueError(f'Installed package version mismatch: {name}')
        license_files = [p.relative_to(server).as_posix() for p in directory.iterdir()
                         if p.is_file() and p.name.lower().startswith(('license', 'copying', 'notice'))]
        packages.append({
            'path': name, 'name': package.get('name', name), 'version': package['version'],
            'integrity': entry.get('integrity'), 'resolved': entry.get('resolved'),
            'license': package.get('license') or original[name].get('license') or 'NOASSERTION',
            'licenseFiles': sorted(license_files),
        })
    return {'schemaVersion': 1, 'packages': packages,
            'licenseReviewRequired': [p['path'] for p in packages if p['license'] == 'NOASSERTION' or not p['licenseFiles']],
            'note': 'Installed-package inventory only, not a complete release SBOM or license clearance.'}


def write_zip(tree, files, directories, archive, level):
    with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=level, allowZip64=False) as package:
        for name in sorted([*files, *(p + '/' for p in directories)]):
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.compress_type = zipfile.ZIP_DEFLATED
            if name.endswith('/'):
                info.external_attr = ((stat.S_IFDIR | 0o700) << 16) | 0x10
                package.writestr(info, b'', compresslevel=level)
            else:
                info.external_attr = (stat.S_IFREG | 0o600) << 16
                # writestr uses bounded individual members (maxFileBytes), and
                # ensures explicit compression level with deterministic headers.
                package.writestr(info, (tree / name).read_bytes(), compresslevel=level)


def main():
    if len(sys.argv) != 1:
        raise ValueError('Usage: python3 tools/payload/prepare.py (uses checked-in locks, no floating branch flags)')
    verify_project()
    lock, policy = read_lock(), read_policy()
    node_version = capture(['node', '-p', 'process.versions.node']).strip()
    npm_version = capture(['npm', '--version']).strip()
    if node_version != lock['node']['version'] or npm_version != policy['npmVersion']:
        raise ValueError(f'Payload build needs Node {lock["node"]["version"]} and npm {policy["npmVersion"]}')
    build = ROOT / 'build'
    build.mkdir(exist_ok=True)
    artifact_root = build / 'payload'
    artifact_root.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='payload-work-', dir=build) as temp:
        work = Path(temp)
        tree = work / 'tree'
        server = tree / 'server'
        server.mkdir(parents=True)
        with (work / 'upstream.tar').open('wb') as archive:
            run(['git', 'archive', '--format=tar', lock['sillytavern']['commit']], cwd=ROOT / 'upstream/SillyTavern', stdout=archive)
        with tarfile.open(work / 'upstream.tar') as archive:
            archive.extractall(server, filter='data')
        source_files = {p.relative_to(server).as_posix(): sha256(p) for p in server.rglob('*') if p.is_file()}
        # A Linux install must not silently omit/select different Android-only
        # production packages. The audited lock has no such selectors.
        check_platform_selection(json.loads((server / 'package-lock.json').read_text())['packages'])
        (work / 'empty.npmrc').write_text('')
        env = {k: v for k, v in os.environ.items() if not k.lower().startswith('npm_config_') and k != 'NODE_OPTIONS'}
        env.update(NODE_ENV='production', NPM_CONFIG_USERCONFIG=str(work / 'empty.npmrc'), NPM_CONFIG_REGISTRY='https://registry.npmjs.org')
        run(['npm', 'ci', '--omit=dev', '--ignore-scripts', '--bin-links=false', '--engine-strict', '--no-audit', '--no-fund'], cwd=server, env=env)
        for name, expected in source_files.items():
            if sha256(server / name) != expected:
                raise ValueError(f'Package preparation modified an upstream source file: {name}')
        for p in server.rglob('*'):
            if p.is_file():
                name = p.relative_to(server).as_posix()
                if not name.startswith('node_modules/') and name not in source_files:
                    raise ValueError(f'Unexpected generated upstream file: {name}')
        (tree / 'shell').mkdir()
        for name in ['bootstrap.mjs', 'policy.mjs']:
            shutil.copy2(ROOT / 'runtime/mobile' / name, tree / 'shell' / name)
        shutil.copy2(ROOT / 'config/mobile-policy.json', tree / 'shell/mobile-policy.json')
        for name in policy['emptyDirectories']:
            safe_name(name)
            (tree / name).mkdir(parents=True, exist_ok=True)
        dependency_inventory = dependencies(server)
        (tree / 'dependency-inventory.json').write_bytes(canonical(dependency_inventory) + b'\n')
        files, directories, total, wasm = inventory(tree, policy)
        package = json.loads((server / 'package.json').read_text())
        content = {
            'schemaVersion': 1,
            'upstream': {**lock['sillytavern'], 'version': package['version']},
            'buildToolchain': {'node': node_version, 'npm': npm_version, 'python': sys.version.split()[0], 'zlib': zlib.ZLIB_RUNTIME_VERSION},
            'recipe': recipe_inputs(),
            'files': files, 'directories': directories, 'totalBytes': total,
            'wasmFiles': wasm, 'nativeAddons': [], 'upstreamPatches': [],
        }
        manifest = {**content, 'payloadId': hashlib.sha256(canonical(content)).hexdigest()}
        manifest_bytes = canonical(manifest) + b'\n'
        temporary_artifact = work / 'artifact'
        temporary_artifact.mkdir()
        (temporary_artifact / 'manifest.json').write_bytes(manifest_bytes)
        write_zip(tree, files, directories, temporary_artifact / 'payload.zip', policy['compressionLevel'])
        verify_zip(temporary_artifact / 'payload.zip', manifest)
        destination = artifact_root / manifest['payloadId']
        archive_hash = sha256(temporary_artifact / 'payload.zip')
        manifest_hash = hashlib.sha256(manifest_bytes).hexdigest()
        if destination.exists():
            if sha256(destination / 'manifest.json') != manifest_hash or sha256(destination / 'payload.zip') != archive_hash:
                raise ValueError('Same payload identity produced different artifact bytes; refusing to overwrite')
        else:
            temporary_artifact.rename(destination)
        current = {'schemaVersion': 1, 'payloadId': manifest['payloadId'],
                   'manifestSha256': manifest_hash, 'archiveSha256': archive_hash,
                   'archiveBytes': (destination / 'payload.zip').stat().st_size,
                   'uncompressedBytes': total, 'fileCount': len(files), 'wasmCount': len(wasm),
                   'installedPackageCount': len(dependency_inventory['packages']),
                   'licenseReviewCount': len(dependency_inventory['licenseReviewRequired'])}
        pointer = artifact_root / 'current.json'
        temporary_pointer = artifact_root / f'.current-{os.getpid()}.tmp'
        temporary_pointer.write_bytes(canonical(current) + b'\n')
        temporary_pointer.replace(pointer)
        print(json.dumps(current, indent=2))
        print(f'PASS: prepared {destination}; Android execution and release licensing are NOT verified')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        sys.exit(f'Payload preparation failed: {error}')
