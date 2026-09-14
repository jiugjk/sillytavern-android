# SPDX-License-Identifier: AGPL-3.0-only
import copy
import hashlib
import json
from pathlib import Path
import stat
import sys
import tempfile
import unittest
import warnings
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools/payload'))
from common import canonical, check_platform_selection, inventory, safe_name, unpack, validate_manifest, verify_zip

POLICY = {'maxEntries': 100, 'maxFileBytes': 1024, 'maxTotalBytes': 4096, 'requiredFiles': ['server/ok.txt']}


def manifest_for(files=None):
    files = files if files is not None else {'server/ok.txt': b'ok'}
    directories = sorted({p.as_posix() for name in files for p in Path(name).parents if str(p) != '.'})
    content = {'schemaVersion': 1, 'files': {name: {'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()} for name, data in files.items()},
               'directories': directories, 'totalBytes': sum(map(len, files.values()))}
    return {**content, 'payloadId': hashlib.sha256(canonical(content)).hexdigest()}


def make_zip(path, files=None, extra=None):
    files = files if files is not None else {'server/ok.txt': b'ok'}
    with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for directory in manifest_for(files)['directories']:
            archive.writestr(directory + '/', b'')
        for name, data in files.items():
            archive.writestr(name, data)
        if extra:
            extra(archive)


class PayloadTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.archive = self.root / 'payload.zip'
        self.manifest = manifest_for()
        make_zip(self.archive)

    def tearDown(self):
        self.temp.cleanup()

    def test_production_platform_selectors_cannot_hide_android_dependencies(self):
        check_platform_selection({'node_modules/plain': {'version': '1'}})
        check_platform_selection({'node_modules/dev-only': {'dev': True, 'os': ['darwin']}})
        for key in ('os', 'cpu', 'libc'):
            with self.subTest(key=key), self.assertRaises(ValueError):
                check_platform_selection({'node_modules/conditional': {key: ['android']}})

    def test_valid_manifest_and_zip(self):
        validate_manifest(self.manifest, POLICY)
        verify_zip(self.archive, self.manifest, policy=POLICY)

    def test_reject_unsafe_names_and_private_state(self):
        for name in ['../outside', '/tmp/escape', 'C:/file', 'server\\file', 'server/../file', 'server//file',
                     'server/./file', 'server/.git/config', 'server/file\0ignored', 'server/config.yaml',
                     'server/data/default-user/secrets.json', 'state/transport.json', 'payload-manifest.json']:
            with self.subTest(name=name), self.assertRaises(ValueError):
                safe_name(name)

    def test_reject_duplicate_members(self):
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            make_zip(self.archive, extra=lambda z: z.writestr('server/ok.txt', b'ok'))
        with self.assertRaises(ValueError):
            verify_zip(self.archive, self.manifest, policy=POLICY)

    def test_reject_missing_and_extra_members(self):
        for files in [{}, {'server/ok.txt': b'ok', 'server/extra.txt': b'extra'}]:
            make_zip(self.archive, files)
            with self.subTest(files=files), self.assertRaises(ValueError):
                verify_zip(self.archive, self.manifest, policy=POLICY)

    def test_reject_modified_content_same_size(self):
        make_zip(self.archive, {'server/ok.txt': b'NO'})
        with self.assertRaises(ValueError):
            verify_zip(self.archive, self.manifest, policy=POLICY)

    def test_reject_changed_manifest_identity(self):
        self.manifest['totalBytes'] = 3
        with self.assertRaises(ValueError):
            validate_manifest(self.manifest, POLICY)

    def test_reject_symlink_member(self):
        entry = zipfile.ZipInfo('server/ok.txt')
        entry.create_system = 3
        entry.external_attr = (stat.S_IFLNK | 0o777) << 16
        with zipfile.ZipFile(self.archive, 'w') as archive:
            archive.writestr('server/', b'')
            archive.writestr(entry, b'ok')
        with self.assertRaises(ValueError):
            verify_zip(self.archive, self.manifest, policy=POLICY)

    def test_reject_native_and_corrupt_wasm_even_with_matching_hash(self):
        for name, data in [('server/renamed.txt', b'\x7fELF1234'), ('server/native.node', b'anything'), ('server/bad.wasm', b'not wasm')]:
            files = {'server/ok.txt': b'ok', name: data}
            make_zip(self.archive, files)
            with self.subTest(name=name), self.assertRaises(ValueError):
                verify_zip(self.archive, manifest_for(files), policy=POLICY)

    def test_size_and_entry_limits(self):
        for key, value in [('maxEntries', 1), ('maxFileBytes', 1), ('maxTotalBytes', 1)]:
            policy = dict(POLICY, **{key: value})
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify_zip(self.archive, self.manifest, policy=policy)

    def test_unpack_is_verified_before_publish_and_never_overwrites(self):
        manifest_bytes = canonical(self.manifest) + b'\n'
        (self.root / 'manifest.json').write_bytes(manifest_bytes)
        expected = hashlib.sha256(manifest_bytes).hexdigest()
        destination = self.root / 'result'
        with self.assertRaises(ValueError):
            unpack(self.root, destination, '0' * 64, POLICY)
        self.assertFalse(destination.exists())
        make_zip(self.archive, {'server/ok.txt': b'NO'})
        with self.assertRaises(ValueError):
            unpack(self.root, destination, expected, POLICY)
        self.assertFalse(destination.exists())
        make_zip(self.archive)
        unpack(self.root, destination, expected, POLICY)
        self.assertEqual((destination / 'server/ok.txt').read_bytes(), b'ok')
        with self.assertRaises(ValueError):
            unpack(self.root, destination, expected, POLICY)
        self.assertEqual((destination / 'server/ok.txt').read_bytes(), b'ok')

    def test_inventory_rejects_filesystem_symlinks(self):
        tree = self.root / 'source'
        (tree / 'server').mkdir(parents=True)
        (tree / 'server/ok.txt').write_text('ok')
        (tree / 'server/link').symlink_to('/tmp')
        with self.assertRaises(ValueError):
            inventory(tree, POLICY)


if __name__ == '__main__':
    unittest.main()
