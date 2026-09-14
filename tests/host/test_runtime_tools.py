# SPDX-License-Identifier: AGPL-3.0-only
import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools'))
from runtime_common import check_elf_text, identity, probe_manifest, read_lock
from verify_runtime_report import REQUIRED_TESTS, verify_report
from verify_apk import check_badging


class ApkPolicyTests(unittest.TestCase):
    good = ("package: name='dev.stshell.probe' versionCode='1'\n"
            "minSdkVersion:'34'\ntargetSdkVersion:'36'\n"
            "native-code: 'arm64-v8a'\napplication-debuggable\n")

    def test_accept_current_and_legacy_aapt_min_sdk_labels(self):
        check_badging(self.good)
        check_badging(self.good.replace('minSdkVersion', 'sdkVersion'))

    def test_reject_wrong_sdk_and_extra_abi(self):
        for text in [self.good.replace("'34'", "'33'"),
                     self.good.replace("native-code: 'arm64-v8a'", "native-code: 'arm64-v8a' 'x86_64'"),
                     self.good.replace('application-debuggable', '')]:
            with self.subTest(text=text), self.assertRaises(ValueError):
                check_badging(text)


class ElfTests(unittest.TestCase):
    header = 'Class: ELF64\nType: DYN (Shared object file)\nMachine: AArch64\n'
    segments = 'LOAD 0x000000 0x000000 0x000000 0x100 0x100 R E 0x4000\n'
    dynamic = '(NEEDED) Shared library: [libc.so]\n'

    def test_accept_android_16k_library(self):
        self.assertEqual(['libc.so'], check_elf_text(self.header, self.segments, self.dynamic))

    def test_reject_4k_library(self):
        with self.assertRaises(ValueError):
            check_elf_text(self.header, self.segments.replace('0x4000', '0x1000'), self.dynamic)

    def test_reject_host_binary(self):
        with self.assertRaises(ValueError):
            check_elf_text(self.header.replace('AArch64', 'Advanced Micro Devices X86-64'), self.segments, self.dynamic)

    def test_reject_termux_or_glibc_dependency(self):
        for dependency in ['libc.so.6', '/data/data/com.termux/files/usr/lib/libssl.so']:
            with self.subTest(dependency=dependency), self.assertRaises(ValueError):
                check_elf_text(self.header, self.segments, f'(NEEDED) Shared library: [{dependency}]')

    def test_reject_missing_segments(self):
        with self.assertRaises(ValueError):
            check_elf_text(self.header, '', self.dynamic)


class ReportTests(unittest.TestCase):
    def setUp(self):
        self.lock = read_lock()
        self.good = {
            'schemaVersion': 1, 'completed': True, 'passed': True,
            'device': {'api': 34, 'abi': 'arm64-v8a', 'is64Bit': True, 'pageSize': 4096},
            'runtimeManifest': {'buildIdentity': identity(self.lock)},
            'probeManifest': probe_manifest(), 'runnerPid': 10, 'runs': [],
        }
        for i in range(2):
            self.good['runs'].append({
                'report': {'mode': 'android', 'platform': 'android', 'arch': 'arm64',
                           'node': self.lock['node']['version'], 'completed': True, 'passed': True,
                           'pid': 20 + i, 'nonce': str(i) * 32,
                           'tests': [{'name': name, 'passed': True} for name in sorted(REQUIRED_TESTS)]},
                'exit': {'exitCode': 0, 'error': None, 'pid': 20 + i, 'nonce': str(i) * 32},
            })

    def test_accept_complete_report(self):
        self.assertEqual(4096, verify_report(self.good, self.lock))

    def test_reject_false_success_flags(self):
        for key in ['completed', 'passed']:
            report = copy.deepcopy(self.good)
            report[key] = False
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify_report(report, self.lock)

    def test_reject_failed_or_skipped_test(self):
        for key, value in [('passed', False), ('skipped', True)]:
            report = copy.deepcopy(self.good)
            report['runs'][0]['report']['tests'][0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify_report(report, self.lock)

    def test_reject_host_old_node_and_stale_lock(self):
        for key, value in [('mode', 'host'), ('platform', 'linux'), ('arch', 'x64'), ('node', '18.20.4')]:
            report = copy.deepcopy(self.good)
            report['runs'][0]['report'][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify_report(report, self.lock)
        self.good['runtimeManifest']['buildIdentity'] = 'stale'
        with self.assertRaises(ValueError):
            verify_report(self.good, self.lock)

    def test_reject_missing_and_duplicated_tests(self):
        self.good['runs'][0]['report']['tests'].pop()
        with self.assertRaises(ValueError):
            verify_report(self.good, self.lock)
        self.good['runs'][0]['report']['tests'].append(self.good['runs'][0]['report']['tests'][0])
        with self.assertRaises(ValueError):
            verify_report(self.good, self.lock)

    def test_reject_native_exit_failure(self):
        self.good['runs'][0]['exit']['exitCode'] = 1
        with self.assertRaises(ValueError):
            verify_report(self.good, self.lock)

    def test_reject_shared_ui_process_and_reused_process(self):
        for pid in [self.good['runnerPid'], self.good['runs'][0]['report']['pid']]:
            report = copy.deepcopy(self.good)
            report['runs'][1]['report']['pid'] = pid
            report['runs'][1]['exit']['pid'] = pid
            with self.subTest(pid=pid), self.assertRaises(ValueError):
                verify_report(report, self.lock)

    def test_reject_changed_probe_implementation(self):
        self.good['probeManifest']['probeIdentity'] = 'old-test-code'
        with self.assertRaises(ValueError):
            verify_report(self.good, self.lock)

    def test_reject_reused_nonce(self):
        self.good['runs'][1]['report']['nonce'] = self.good['runs'][0]['report']['nonce']
        self.good['runs'][1]['exit']['nonce'] = self.good['runs'][0]['report']['nonce']
        with self.assertRaises(ValueError):
            verify_report(self.good, self.lock)


if __name__ == '__main__':
    unittest.main()
