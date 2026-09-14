#!/usr/bin/env python3
"""Run instrumentation and collect fresh JSON from our debuggable probe, never Termux."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys

from runtime_common import ROOT, capture, read_lock, run
from verify_runtime_report import verify_report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL'))
    args = parser.parse_args()
    if not args.serial:
        raise ValueError('Specify --serial or ANDROID_SERIAL; no device must never count as a pass')
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        raise ValueError('Set ANDROID_HOME')
    adb = [str(Path(sdk) / 'platform-tools/adb'), '-s', args.serial]
    if capture([*adb, 'get-state']).strip() != 'device':
        raise ValueError('Selected device is not ready/authorized')
    if int(capture([*adb, 'shell', 'getprop', 'ro.build.version.sdk']).strip()) < 34:
        raise ValueError('Android 14+ required')
    if 'arm64-v8a' not in capture([*adb, 'shell', 'getprop', 'ro.product.cpu.abilist']):
        raise ValueError('Actual arm64 device required; x86 emulation is not arm64 validation')
    page_size = int(capture([*adb, 'shell', 'getconf', 'PAGE_SIZE']).strip())
    if page_size not in (4096, 16384):
        raise ValueError(f'Unsupported device page size: {page_size}')
    env = dict(os.environ, ANDROID_SERIAL=args.serial)
    run([ROOT / 'android/gradlew', ':runtime-probe:installDebug'], cwd=ROOT / 'android', env=env)
    package = 'dev.stshell.probe'
    run([*adb, 'shell', 'am', 'force-stop', package])
    # Remove the old aggregate before testing so collection cannot accept an old PASS.
    run([*adb, 'shell', 'run-as', package, 'rm', '-f', 'files/runtime-probe-device.json'])
    test_error = None
    try:
        run([ROOT / 'android/gradlew', ':runtime-probe:connectedDebugAndroidTest'], cwd=ROOT / 'android', env=env)
    except subprocess.CalledProcessError as error:
        test_error = error
    reports = ROOT / 'build/reports'
    reports.mkdir(parents=True, exist_ok=True)
    safe_serial = re.sub(r'[^A-Za-z0-9_.-]', '_', args.serial)
    # Preserve both runs when the same phone reboots between 4 KB and 16 KB.
    destination = reports / f'device-{safe_serial}-{page_size}.json'
    destination.unlink(missing_ok=True)
    try:
        data = capture([*adb, 'exec-out', 'run-as', package, 'cat', 'files/runtime-probe-device.json'])
        report = json.loads(data)
        destination.write_text(json.dumps(report, indent=2) + '\n')
    finally:
        if test_error:
            raise test_error
    pages = verify_report(report, read_lock())
    if pages != page_size:
        raise ValueError('Device page size changed during the test run')
    print(f'PASS on this device ({pages} byte pages): {destination}')
    print('Run verify_runtime_report.py across both 4 KB and 16 KB reports; this is not a full M1/ST pass.')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError) as error:
        sys.exit(f'Device probe failed: {error}')
