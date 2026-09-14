"""Shared checks for the Node 26 / Android arm64 experiment (stdlib only)."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def read_lock():
    lock = json.loads((ROOT / 'upstream.lock.json').read_text())
    if lock['node']['version'].split('.')[0] != '26':
        raise ValueError('Node 26 is required; refusing a downgraded lock')
    if lock['android']['abi'] != 'arm64-v8a' or lock['android']['minSdk'] != 34:
        raise ValueError('Only Android 14+ / arm64-v8a is approved')
    for patch in lock['node']['patches']:
        if sha256(ROOT / patch['path']) != patch['sha256']:
            raise ValueError(f"Patch checksum mismatch: {patch['path']}")
    return lock


def identity(lock):
    inputs = {'node': lock['node'], 'android': lock['android']}
    return hashlib.sha256(json.dumps(inputs, sort_keys=True).encode()).hexdigest()


def probe_manifest():
    # Include actual test/bridge code, not just libnode's build identity: changing
    # probe assertions must invalidate old device reports even on the same Node.
    paths = set()
    for directory in [ROOT / 'runtime/probe', ROOT / 'android/runtime-probe/src']:
        paths.update(p for p in directory.rglob('*') if p.is_file())
    paths.update((ROOT / 'tools').glob('*.py'))
    paths.update(ROOT / name for name in [
        'upstream.lock.json', 'android/runtime-probe/build.gradle.kts',
        'android/build.gradle.kts', 'android/settings.gradle.kts',
    ])
    files = {p.relative_to(ROOT).as_posix(): sha256(p) for p in sorted(paths)}
    digest = hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()
    return {'schemaVersion': 1, 'probeIdentity': digest, 'files': files}


def sdk_paths(lock):
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        raise ValueError('Set ANDROID_HOME to the Android SDK directory')
    sdk = Path(sdk).resolve()
    ndk = sdk / 'ndk' / lock['android']['ndkVersion']
    tc = ndk / 'toolchains/llvm/prebuilt/linux-x86_64'
    ninja = sdk / 'cmake' / lock['android']['cmakeVersion'] / 'bin/ninja'
    for path in (tc / 'bin/clang', ninja):
        if not path.is_file():
            raise ValueError(f'Missing SDK tool: {path}; see README.md')
    return ndk, tc, ninja


def run(args, **kwargs):
    print('+', ' '.join(map(str, args)), flush=True)
    return subprocess.run(list(map(str, args)), check=True, **kwargs)


def capture(args):
    return subprocess.check_output(list(map(str, args)), text=True)


def check_elf_text(header, segments, dynamic, extra_dependencies=()):
    if not re.search(r'Class:\s+ELF64', header) or not re.search(r'Machine:\s+AArch64', header):
        raise ValueError('Runtime must be an ELF64 AArch64 library, not a host binary')
    if not re.search(r'Type:\s+DYN', header):
        raise ValueError('Runtime must be a shared object')
    loads = [line.split() for line in segments.splitlines() if line.strip().startswith('LOAD ')]
    if not loads:
        raise ValueError('ELF has no LOAD segments')
    for fields in loads:
        alignment = int(fields[-1], 16)
        offset, virtual = int(fields[1], 16), int(fields[2], 16)
        if alignment < 16384 or alignment & (alignment - 1) or (virtual - offset) % 16384:
            raise ValueError('ELF LOAD segments are not 16 KB compatible')
    needed = re.findall(r'\(NEEDED\).*\[(.*?)\]', dynamic)
    allowed = {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so', 'libc++_shared.so'} | set(extra_dependencies)
    if set(needed) - allowed:
        raise ValueError(f'Unexpected native dependencies: {sorted(set(needed) - allowed)}')
    return needed


def inspect_elf(path, readelf, extra_dependencies=()):
    return check_elf_text(capture([readelf, '-hW', path]),
                          capture([readelf, '-lW', path]), capture([readelf, '-dW', path]), extra_dependencies)


def verify_artifacts(directory, lock, readelf):
    directory = Path(directory)
    manifest = json.loads((directory / 'manifest.json').read_text())
    if manifest.get('buildIdentity') != identity(lock):
        raise ValueError('Runtime artifacts belong to a different source/toolchain lock; rebuild')
    files = manifest.get('files', {})
    if not {'lib/libnode.so', 'include/node/node.h'} <= files.keys():
        raise ValueError('Runtime manifest omits required files')
    if {str(p.relative_to(directory)) for p in directory.rglob('*') if p.is_file() and p.name != 'manifest.json'} != set(files):
        raise ValueError('Runtime artifact file inventory mismatch')
    for name, expected in files.items():
        path = (directory / name).resolve()
        if not path.is_relative_to(directory.resolve()) or sha256(path) != expected:
            raise ValueError(f'Runtime checksum/path mismatch: {name}')
    node_needed = inspect_elf(directory / 'lib/libnode.so', readelf)
    if 'libc++_shared.so' in node_needed and 'lib/libc++_shared.so' not in files:
        raise ValueError('Missing required libc++_shared.so')
    for path in (directory / 'lib').glob('*.so'):
        inspect_elf(path, readelf)
    symbols = capture([readelf, '--dyn-syms', '--wide', directory / 'lib/libnode.so'])
    if not re.search(r'GLOBAL\s+DEFAULT\s+\d+\s+_ZN4node5StartEiPPc(?:\s|$)', symbols):
        raise ValueError('libnode does not export node::Start(int, char**)')
    return manifest
