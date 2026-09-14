#!/usr/bin/env python3
"""Verify source pins and the diagnostic Android project's fixed policy."""
import subprocess
import sys
from runtime_common import ROOT, capture, read_lock, sha256


def main():
    lock = read_lock()
    upstream = ROOT / 'upstream/SillyTavern'
    if capture(['git', 'config', '-f', ROOT / '.gitmodules', '--get', 'submodule.upstream/SillyTavern.url']).strip() != lock['sillytavern']['url']:
        raise ValueError('Submodule URL must point to the official SillyTavern repository')
    if capture(['git', 'config', '-f', ROOT / '.gitmodules', '--get', 'submodule.upstream/SillyTavern.branch']).strip() != 'staging':
        raise ValueError('Submodule tracking branch must be staging; builds still use the exact commit')
    if capture(['git', '-C', upstream, 'rev-parse', 'HEAD']).strip() != lock['sillytavern']['commit']:
        raise ValueError('SillyTavern submodule is not at the locked commit')
    if capture(['git', '-C', upstream, 'status', '--porcelain']).strip():
        raise ValueError('SillyTavern submodule is modified/untracked; adaptations belong outside it')
    if sha256(upstream / 'package-lock.json') != lock['sillytavern']['packageLockSha256']:
        raise ValueError('SillyTavern package-lock.json checksum mismatch')
    if sha256(ROOT / 'android/gradle/wrapper/gradle-wrapper.jar') != lock['android']['gradleWrapperJarSha256']:
        raise ValueError('Gradle wrapper JAR checksum mismatch')
    module = (ROOT / 'android/runtime-probe/build.gradle.kts').read_text()
    for key in ['minSdk', 'targetSdk', 'compileSdk']:
        if f"{key} = {lock['android'][key]}" not in module:
            raise ValueError(f'Android {key} drift')
    for key in ['ndkVersion', 'buildToolsVersion']:
        if f'{key} = "{lock["android"][key]}"' not in module:
            raise ValueError(f'Android {key} drift')
    if 'abiFilters += "arm64-v8a"' not in module:
        raise ValueError('Missing arm64-only policy')
    wrapper = (ROOT / 'android/gradle/wrapper/gradle-wrapper.properties').read_text()
    if f'distributionSha256Sum={lock["android"]["gradleDistributionSha256"]}' not in wrapper:
        raise ValueError('Gradle distribution checksum drift')
    print('PASS: official unmodified ST submodule, pinned sources/tooling and Android14+ arm64 policy')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        sys.exit(f'Project verification failed: {error}')
