#!/usr/bin/env python3
"""Build the pinned official Node source as Android libnode; never edit ST."""
import argparse
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request

from runtime_common import ROOT, capture, identity, inspect_elf, read_lock, run, sdk_paths, sha256, verify_artifacts


def prepare_source(lock):
    source = ROOT / 'build/node-source' / f"node-v{lock['node']['version']}"
    stamp = source / '.st-shell-source.json'
    if source.exists():
        if not stamp.is_file() or json.loads(stamp.read_text()).get('buildIdentity') != identity(lock):
            raise ValueError(f'Unrecognized/stale build directory: {source}. Move it aside before a clean rebuild.')
        return source
    downloads = ROOT / 'build/downloads'
    downloads.mkdir(parents=True, exist_ok=True)
    archive = downloads / f"node-v{lock['node']['version']}.tar.xz"
    if not archive.exists():
        partial = archive.with_suffix('.partial')
        with urllib.request.urlopen(lock['node']['url'], timeout=90) as response, partial.open('wb') as output:
            shutil.copyfileobj(response, output)
        if sha256(partial) != lock['node']['sha256']:
            raise ValueError('Downloaded Node source checksum mismatch')
        partial.replace(archive)
    if sha256(archive) != lock['node']['sha256']:
        raise ValueError('Cached Node source checksum mismatch')
    source.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=source.parent) as tmp:
        with tarfile.open(archive) as package:
            package.extractall(tmp, filter='data')
        Path(tmp, source.name).replace(source)
    # git apply from the shell repository root: invoking it in an ignored nested
    # directory can silently skip every patch! --directory and source assertions
    # below deliberately avoid that trap.
    prefix = source.relative_to(ROOT)
    for patch in lock['node']['patches']:
        run(['git', 'apply', '--check', f'--directory={prefix}', patch['path']], cwd=ROOT)
        run(['git', 'apply', f'--directory={prefix}', patch['path']], cwd=ROOT)
    stamp.write_text(json.dumps({'buildIdentity': identity(lock)}, indent=2) + '\n')
    return source


def prepare_ndk_sources(source, lock, ndk):
    # GYP/Ninja rejects absolute source paths. Copy verified, unmodified NDK
    # inputs into the generated Node tree; do not patch/install anything in NDK.
    destination = source / 'deps/zlib/android-cpufeatures'
    destination.mkdir(exist_ok=True)
    for name, expected in lock['android']['ndkCpuFeaturesInputs'].items():
        origin = ndk / 'sources/android/cpufeatures' / name
        if sha256(origin) != expected:
            raise ValueError(f'NDK cpufeatures input checksum mismatch: {name}')
        target = destination / name
        if not target.exists() or sha256(target) != expected:
            shutil.copy2(origin, target)


def stage(source, lock, tc):
    artifacts = ROOT / 'build/runtime'
    artifacts.parent.mkdir(parents=True, exist_ok=True)
    candidates = list((source / 'out/Release').glob('lib/libnode.so'))
    if len(candidates) != 1:
        raise ValueError('Expected exactly out/Release/lib/libnode.so after the shared build')
    with tempfile.TemporaryDirectory(prefix='runtime-stage-', dir=artifacts.parent) as tmp:
        dest = Path(tmp)
        (dest / 'lib').mkdir()
        shutil.copy2(candidates[0], dest / 'lib/libnode.so')
        run([tc / 'bin/llvm-strip', '--strip-unneeded', dest / 'lib/libnode.so'])
        readelf = tc / 'bin/llvm-readelf'
        needed = inspect_elf(dest / 'lib/libnode.so', readelf)
        if 'libc++_shared.so' in needed:
            shutil.copy2(tc / 'sysroot/usr/lib/aarch64-linux-android/libc++_shared.so', dest / 'lib/libc++_shared.so')
        run([sys.executable, source / 'tools/install.py', 'install', '--headers-only',
             '--dest-dir', dest, '--prefix', '/'], cwd=source)
        (dest / 'licenses').mkdir()
        shutil.copy2(source / 'LICENSE', dest / 'licenses/Node.txt')
        shutil.copy2(source / 'deps/zlib/android-cpufeatures/NOTICE', dest / 'licenses/Android-cpufeatures.txt')
        manifest = {
            'schemaVersion': 1,
            'buildIdentity': identity(lock),
            'nodeVersion': lock['node']['version'],
            'abi': 'arm64-v8a',
            'minSdk': 34,
            'sourceSha256': lock['node']['sha256'],
            'configureArgs': lock['node']['configureArgs'],
            'compiler': capture([tc / 'bin/clang', '--version']).strip(),
            'runtimeTested': False,
            'knownDifferences': [
                'Temporal disabled explicitly for this probe build; full ICU retained',
                'OpenSSL assembly disabled per the upstream Android configure recipe; TLS retained',
            ],
            'files': {str(p.relative_to(dest)): sha256(p) for p in sorted(dest.rglob('*')) if p.is_file()},
        }
        (dest / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
        verify_artifacts(dest, lock, readelf)
        if artifacts.exists():
            shutil.rmtree(artifacts)
        shutil.copytree(dest, artifacts)
    print(f'Static artifact checks passed: {artifacts}. Android execution is NOT yet verified.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jobs', type=int, default=min(12, os.cpu_count() or 1))
    parser.add_argument('--prepare-only', action='store_true')
    parser.add_argument('--stage-only', action='store_true', help='stage an already completed build for this lock')
    args = parser.parse_args()
    if args.jobs < 1:
        parser.error('--jobs must be positive')
    if platform.system() != 'Linux' or platform.machine() != 'x86_64':
        raise ValueError('This initial cross-build recipe supports Linux x86_64 hosts only')
    lock = read_lock()
    ndk, tc, ninja = sdk_paths(lock)
    source = prepare_source(lock)
    prepare_ndk_sources(source, lock, ndk)
    if args.prepare_only:
        return
    if not args.stage_only:
        host_atomic = Path(capture(['g++', '-print-file-name=libatomic.so']).strip())
        if not host_atomic.is_file():
            raise ValueError('Install the host GCC libatomic development library (needed by Linux mksnapshot)')
        env = os.environ.copy()
        env.update({
            'PATH': f"{ninja.parent}:{tc / 'bin'}:{env.get('PATH', '')}",
            'CC': str(tc / 'bin/aarch64-linux-android34-clang'),
            'CXX': str(tc / 'bin/aarch64-linux-android34-clang++'),
            'CC_host': str(tc / 'bin/clang'), 'CXX_host': str(tc / 'bin/clang++'),
            'AR': str(tc / 'bin/llvm-ar'),
            'GYP_DEFINES': f'target_arch=arm64 v8_target_arch=arm64 android_target_arch=arm64 host_os=linux OS=android android_ndk_path={ndk}',
            'LDFLAGS': '-Wl,-z,max-page-size=16384',
            # NDK Clang also ships a libatomic.a compatibility stub. Prefer the
            # real host GCC library for Linux snapshot tools, never for Android.
            'LDFLAGS_host': f'-L{host_atomic.parent}',
        })
        # Avoid silently inheriting host package-manager/compiler overrides.
        for key in ['CFLAGS', 'CXXFLAGS', 'CPPFLAGS', 'CC_target', 'CXX_target']:
            env.pop(key, None)
        run([source / 'configure', *lock['node']['configureArgs']], cwd=source, env=env)
        run([ninja, '-C', 'out/Release', f'-j{args.jobs}', 'libnode'], cwd=source, env=env)
    stage(source, lock, tc)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        sys.exit(f'Runtime build failed: {error}')
