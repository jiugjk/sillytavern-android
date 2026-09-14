# SPDX-License-Identifier: AGPL-3.0-only
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
from runtime_common import identity, read_lock, sha256


def contract():
    lock = read_lock()
    pointer = json.loads((ROOT / 'build/payload/current.json').read_text())
    paths = set((ROOT / 'android-app/app/src').rglob('*'))
    paths.update((ROOT / 'tools/app').glob('*.py'))
    paths.update((ROOT / 'runtime/android').glob('*.mjs'))
    paths.update((ROOT / 'runtime/android').glob('*.js'))
    paths.update(ROOT / p for p in ['upstream.lock.json', 'tools/icons/manifest.json', 'android-app/settings.gradle.kts',
        'android-app/build.gradle.kts', 'android-app/gradle.properties', 'android-app/version.properties',
        'android-app/app/build.gradle.kts',
        'android/runtime-probe/src/main/java/dev/stshell/probe/NativeNode.kt',
        'android/runtime-probe/src/main/cpp/CMakeLists.txt', 'android/runtime-probe/src/main/cpp/node_bridge.cpp'])
    inputs = {p.relative_to(ROOT).as_posix(): sha256(p) for p in sorted(paths) if p.is_file()}
    data = {'schemaVersion': 1, 'payload': pointer, 'runtimeBuildIdentity': identity(lock),
            'nodeVersion': lock['node']['version'], 'minSdk': 34, 'abi': 'arm64-v8a',
            'inputs': inputs}
    data['appIdentity'] = hashlib.sha256(json.dumps(data, sort_keys=True).encode()).hexdigest()
    return data
