#!/usr/bin/env python3
"""Reject host, incomplete, failed, stale or missing runtime-probe device reports."""
import argparse
import json
import sys
from pathlib import Path

from runtime_common import identity, probe_manifest, read_lock

REQUIRED_TESTS = frozenset({
    'runtime-identity', 'esm-tla', 'fs-atomic', 'crypto', 'icu-unicode', 'wasm',
    'wasm-simd', 'worker', 'fetch-http-stream', 'dns', 'tls-validation', 'https-remote',
})


def verify_report(report, lock):
    if report.get('schemaVersion') != 1 or report.get('completed') is not True or report.get('passed') is not True:
        raise ValueError('Device test run did not complete successfully')
    device = report['device']
    if device['api'] < 34 or device['abi'] != 'arm64-v8a' or device['is64Bit'] is not True:
        raise ValueError('Device is not Android 14+ / arm64')
    if device['pageSize'] not in (4096, 16384):
        raise ValueError('Unrecognized device page size')
    if report['runtimeManifest']['buildIdentity'] != identity(lock):
        raise ValueError('Device tested an outdated/different runtime build')
    if report.get('probeManifest', {}).get('probeIdentity') != probe_manifest()['probeIdentity']:
        raise ValueError('Device tested an outdated/different probe implementation')
    runs = report['runs']
    if len(runs) != 2:
        raise ValueError('Two separate Node service-process runs are required')
    pids, nonces = set(), set()
    for entry in runs:
        probe, native_exit = entry['report'], entry['exit']
        if probe.get('mode') != 'android' or probe.get('platform') != 'android' or probe.get('arch') != 'arm64':
            raise ValueError('Host baseline cannot satisfy the Android runtime gate')
        if probe.get('node') != lock['node']['version']:
            raise ValueError('Runtime Node version is not the pinned Node 26 version')
        if probe.get('completed') is not True or probe.get('passed') is not True:
            raise ValueError('Incomplete or failed Node probe')
        tests = probe['tests']
        if len(tests) != len(REQUIRED_TESTS) or {t['name'] for t in tests} != REQUIRED_TESTS:
            raise ValueError('Missing/duplicated capability tests')
        if any(t.get('passed') is not True or t.get('skipped') for t in tests):
            raise ValueError('A failed/skipped capability is not a pass')
        if native_exit['exitCode'] != 0 or native_exit.get('error') is not None:
            raise ValueError('Native entry point failed after/before the JS probe')
        pid, nonce = probe['pid'], probe['nonce']
        if pid != native_exit['pid'] or pid == report['runnerPid'] or pid in pids:
            raise ValueError('Node did not use separate restartable service processes')
        if nonce != native_exit['nonce'] or nonce in nonces:
            raise ValueError('Reused or mismatched probe nonce')
        pids.add(pid)
        nonces.add(nonce)
    return device['pageSize']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reports', type=Path, required=True, help='directory of device-*.json collected after instrumentation')
    parser.add_argument('--require-pages', nargs='+', type=int, choices=[4096, 16384], default=[4096, 16384])
    args = parser.parse_args()
    files = sorted(args.reports.glob('device-*.json'))
    if not files:
        raise ValueError('No device reports: connect arm64 devices and run tools/run_device_probe.py')
    lock = read_lock()
    pages = {verify_report(json.loads(p.read_text()), lock) for p in files}
    missing = set(args.require_pages) - pages
    if missing:
        raise ValueError(f'Missing page-size coverage: {sorted(missing)}. No Android M1 pass can be claimed.')
    print(f'PASS: runtime capability reports ({sorted(pages)} byte pages). ST/business/background gates remain separate.')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, TypeError) as error:
        sys.exit(f'Runtime report verification failed: {error}')
