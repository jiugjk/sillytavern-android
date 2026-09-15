# SPDX-License-Identifier: AGPL-3.0-only
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/app'))


def load(name, relative) -> Any:
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    if spec is None or spec.loader is None:
        raise ImportError(relative)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class BuildReviewTests(unittest.TestCase):
    def test_version_space_variants_round_trip(self):
        module = load('review_bump', 'tools/ci/bump_version.py')
        with tempfile.TemporaryDirectory() as tmp:
            module.VERSION_FILE = Path(tmp) / 'version.properties'
            for content in ('versionCode=11\nversionName=0.4.7\n',
                            'versionCode = 11\nversionName = 0.4.7\n',
                            '  versionCode = 11\n\tversionName = 0.4.7\n'):
                module.VERSION_FILE.write_text(content)
                self.assertEqual(module.read(), (11, '0.4.7'))
                module.write(12, '0.4.8')
                self.assertEqual(module.read(), (12, '0.4.8'))

    def test_duplicate_version_definition_is_rejected(self):
        module = load('review_bump', 'tools/ci/bump_version.py')
        with self.assertRaises(ValueError):
            module.parse('versionCode=11\nversionName=0.4.7\nversionCode=12\n')

    def test_managed_assets_delete_and_rename_match_clean_build(self):
        module = load('review_prepare', 'tools/app/prepare.py')
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); source = root / 'source'; source.write_text('content')
            incremental, clean = root / 'incremental', root / 'clean'
            module.publish(incremental, {'app/old.js': source}, {'contract.json': '{}'})
            module.publish(incremental, {'app/new.js': source}, {'contract.json': '{}'})
            module.publish(clean, {'app/new.js': source}, {'contract.json': '{}'})
            def tree(directory):
                return {p.relative_to(directory).as_posix(): p.read_bytes() for p in directory.rglob('*') if p.is_file()}
            self.assertEqual(tree(incremental), tree(clean))
            self.assertFalse((incremental / 'app/old.js').exists())

    def test_release_shortened_resource_names_resolve_by_identity(self):
        module = load('review_verify', 'tools/app/verify.py')
        resources = module.resource_files('''    resource 0x7f080000 mipmap/ic_launcher
      (ldpi) (file) res/N3.png type=PNG
      (anydpi-v33) (file) res/IG.xml type=XML
    resource 0x7f040008 drawable/ic_stat_sillytavern
      () (file) res/8Q.xml type=XML
''')
        self.assertEqual(module.resource_path(resources, 'mipmap/ic_launcher', 'ldpi'), 'res/N3.png')
        self.assertEqual(module.resource_path(resources, 'mipmap/ic_launcher', 'anydpi'), 'res/IG.xml')
        self.assertEqual(module.resource_path(resources, 'drawable/ic_stat_sillytavern', ''), 'res/8Q.xml')
        with self.assertRaises(ValueError):
            module.resource_path(resources, 'mipmap/ic_launcher', 'xxxhdpi')

    def test_icon_gate_rejects_tampering_even_with_python_optimization(self):
        metadata = json.loads((ROOT / 'tools/icons/manifest.json').read_text())
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            paths = ['tools/icons/verify.py', 'tools/icons/manifest.json',
                     'android-app/app/src/main/AndroidManifest.xml', *metadata['sources'],
                     *['android-app/app/src/main/res/' + name for name in metadata['files']]]
            for name in paths:
                dest = root / name; dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(ROOT / name, dest)
            icon = root / 'android-app/app/src/main/res/drawable/ic_stat_sillytavern.xml'
            icon.write_text(icon.read_text().replace('#FFFFFFFF', '#FF00FF00'))
            for flags in ([], ['-O']):
                result = subprocess.run([sys.executable, *flags, root / 'tools/icons/verify.py'], capture_output=True, text=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('Generated resource hash mismatch', result.stderr)
                self.assertNotIn('PASS:', result.stdout)


if __name__ == '__main__':
    unittest.main()
