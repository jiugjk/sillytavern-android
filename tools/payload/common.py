# SPDX-License-Identifier: AGPL-3.0-only
"""Payload inventory, bounded ZIP verification, and extraction (stdlib only)."""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
from runtime_common import sha256, read_lock


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode('utf-8')


def read_policy():
    return json.loads((ROOT / 'config/payload-policy.json').read_text())


def recipe_inputs():
    paths = [*sorted((ROOT / 'tools/payload').glob('*.py')),
             ROOT / 'config/payload-policy.json', ROOT / 'config/mobile-policy.json',
             *sorted((ROOT / 'runtime/mobile').glob('*.mjs'))]
    return {p.relative_to(ROOT).as_posix(): sha256(p) for p in paths}


def check_platform_selection(packages):
    for name, entry in packages.items():
        if not entry.get('dev') and any(entry.get(key) for key in ('os', 'cpu', 'libc')):
            raise ValueError(f'Platform-selective production package requires Android-specific review: {name}')


def safe_name(name):
    if not isinstance(name, str) or not name or re.search(r'[\\:\x00-\x1f]', name):
        raise ValueError(f'Unsafe payload name: {name!r}')
    p = PurePosixPath(name)
    if p.is_absolute() or p.as_posix() != name or any(part in ('', '.', '..') for part in name.split('/')):
        raise ValueError(f'Unsafe payload path: {name!r}')
    if '.git' in p.parts or name == 'payload-manifest.json':
        raise ValueError(f'Forbidden payload path: {name}')
    if not (p.parts[0] in ('server', 'shell') or name == 'dependency-inventory.json'):
        raise ValueError(f'Unexpected payload root: {name}')
    if name in ('server/config.yaml', 'server/whitelist.txt') or (name.startswith('server/data/') and name != 'server/data/.gitkeep'):
        raise ValueError(f'Private/runtime state cannot be shipped in a payload: {name}')
    return name


def check_content(name, head):
    basename = PurePosixPath(name).name
    if head.startswith(b'\x7fELF') or head[:4] in (b'\xcf\xfa\xed\xfe', b'\xfe\xed\xfa\xcf') or re.search(r'\.(node|so(?:\.\d+)*|dylib|dll|exe)$', basename, re.I) or basename == 'binding.gyp':
        raise ValueError(f'Unexpected native dependency/build entry: {name}')
    if name.endswith('.wasm') and head[:8] != b'\0asm\x01\0\0\0':
        raise ValueError(f'Invalid WASM header: {name}')


def inventory(tree, policy):
    files, directories = {}, []
    total, wasm = 0, []
    for current, names, filenames in os.walk(tree, followlinks=False):
        for name in sorted(names + filenames):
            p = Path(current) / name
            relative = safe_name(p.relative_to(tree).as_posix())
            info = p.lstat()
            if stat.S_ISDIR(info.st_mode):
                directories.append(relative)
                continue
            if not stat.S_ISREG(info.st_mode):
                raise ValueError(f'Symlink/special file is not allowed in a payload: {relative}')
            if info.st_size > policy['maxFileBytes']:
                raise ValueError(f'Payload member exceeds size bound: {relative}')
            total += info.st_size
            if total > policy['maxTotalBytes']:
                raise ValueError('Payload exceeds total uncompressed size bound')
            with p.open('rb') as stream:
                magic = stream.read(8)
            check_content(relative, magic)
            if name.endswith('.wasm'):
                wasm.append(relative)
            files[relative] = {'size': info.st_size, 'sha256': sha256(p)}
    if len(files) + len(directories) > policy['maxEntries']:
        raise ValueError('Too many payload entries')
    missing = set(policy['requiredFiles']) - files.keys()
    if missing:
        raise ValueError(f'Missing critical payload files: {sorted(missing)}')
    return dict(sorted(files.items())), sorted(directories), total, sorted(wasm)


def validate_manifest(manifest, policy=None):
    policy = policy or read_policy()
    if manifest.get('schemaVersion') != 1 or not re.fullmatch('[a-f0-9]{64}', manifest.get('payloadId', '')):
        raise ValueError('Invalid payload manifest identity/schema')
    content = dict(manifest)
    content.pop('payloadId')
    if hashlib.sha256(canonical(content)).hexdigest() != manifest['payloadId']:
        raise ValueError('Payload content identity mismatch')
    files, directories = manifest['files'], manifest['directories']
    if not isinstance(files, dict) or not isinstance(directories, list):
        raise ValueError('Invalid file inventory')
    if len(files) + len(directories) > policy['maxEntries']:
        raise ValueError('Too many entries in payload manifest')
    seen, total = set(), 0
    for name in directories:
        safe_name(name)
        if name in seen or name in files:
            raise ValueError('Duplicate/conflicting directory')
        seen.add(name)
    for name, entry in files.items():
        safe_name(name)
        size = entry['size']
        if type(size) is not int or not 0 <= size <= policy['maxFileBytes'] or not re.fullmatch('[a-f0-9]{64}', entry['sha256']):
            raise ValueError(f'Invalid member metadata: {name}')
        total += size
        for parent in PurePosixPath(name).parents:
            if str(parent) != '.' and str(parent) not in seen:
                raise ValueError(f'Missing/conflicting parent directory: {name}')
    if total > policy['maxTotalBytes'] or total != manifest['totalBytes']:
        raise ValueError('Invalid total payload size')
    if set(policy['requiredFiles']) - files.keys():
        raise ValueError('Manifest omits critical runtime resources')
    return manifest


def verify_zip(archive, manifest, destination=None, policy=None):
    """Validate all names/metadata/content before publishing any extracted directory."""
    policy = policy or read_policy()
    validate_manifest(manifest, policy)
    wanted_files = manifest['files']
    wanted_dirs = set(manifest['directories'])
    with zipfile.ZipFile(archive) as package:
        entries = package.infolist()
        if len(entries) > policy['maxEntries']:
            raise ValueError('ZIP has too many entries')
        seen = set()
        for entry in entries:
            name = entry.filename[:-1] if entry.is_dir() else entry.filename
            safe_name(name)
            if entry.orig_filename != entry.filename or name in seen:
                raise ValueError('Ambiguous or duplicate ZIP member')
            seen.add(name)
            mode = stat.S_IFMT(entry.external_attr >> 16)
            if mode not in (0, stat.S_IFDIR if entry.is_dir() else stat.S_IFREG):
                raise ValueError('ZIP symlinks/special files are forbidden')
            if entry.flag_bits & 1 or entry.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                raise ValueError('Encrypted/unsupported ZIP member')
            if entry.is_dir():
                if name not in wanted_dirs or entry.file_size:
                    raise ValueError('Unexpected ZIP directory')
            else:
                if name not in wanted_files or entry.file_size != wanted_files[name]['size']:
                    raise ValueError(f'ZIP inventory/size mismatch: {name}')
        if seen != set(wanted_files) | wanted_dirs:
            raise ValueError('ZIP member inventory is incomplete')
        if destination:
            destination = Path(destination)
            for name in sorted(wanted_dirs, key=lambda p: (p.count('/'), p)):
                (destination / name).mkdir(mode=0o700, parents=True, exist_ok=True)
        for entry in entries:
            if entry.is_dir():
                continue
            expected = wanted_files[entry.filename]
            digest, size, head = hashlib.sha256(), 0, b''
            output = None
            try:
                if destination:
                    output = (destination / entry.filename).open('xb')
                with package.open(entry) as stream:
                    while block := stream.read(1024 * 1024):
                        if size == 0:
                            head = block[:8]
                            check_content(entry.filename, head)
                        size += len(block)
                        if size > expected['size']:
                            raise ValueError('ZIP expands beyond declared size')
                        digest.update(block)
                        if output:
                            output.write(block)
                check_content(entry.filename, head)
                if size != expected['size'] or digest.hexdigest() != expected['sha256']:
                    raise ValueError(f'ZIP content hash mismatch: {entry.filename}')
            finally:
                if output:
                    output.close()
                    os.chmod(destination / entry.filename, 0o600)


def unpack(artifact, destination, expected_manifest_sha256, policy=None):
    artifact, destination = Path(artifact).resolve(), Path(destination).absolute()
    if destination.exists() or destination.is_symlink():
        raise ValueError('Extraction destination must not exist; never overwrite live state/payloads')
    manifest_path = artifact / 'manifest.json'
    if manifest_path.stat().st_size > 32 * 1024 * 1024:
        raise ValueError('Manifest is too large')
    if sha256(manifest_path) != expected_manifest_sha256:
        raise ValueError('Manifest differs from the trusted expected hash')
    data = manifest_path.read_bytes()
    manifest = json.loads(data)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.payload-extract-', dir=destination.parent) as tmp:
        temporary = Path(tmp) / 'tree'
        temporary.mkdir(mode=0o700)
        verify_zip(artifact / 'payload.zip', manifest, temporary, policy)
        (temporary / 'payload-manifest.json').write_bytes(data)
        # Directory publication happens only after complete validation. Caller
        # must serialize installations; this does not implement app upgrades.
        if destination.exists() or destination.is_symlink():
            raise ValueError('Extraction destination appeared during validation')
        temporary.rename(destination)
    return manifest
