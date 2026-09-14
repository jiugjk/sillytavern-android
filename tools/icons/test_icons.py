# SPDX-License-Identifier: AGPL-3.0-only
import io
import math
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

from generate import ANDROID, DENSITIES, RES, ROOT, SAFE_DIAMETER, SOURCE, SOURCE_HASH, artwork, generated, sha
from PIL import Image


class IconTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.outputs = generated()

    def test_all_density_assets_have_exact_dimensions(self):
        for density, size in DENSITIES.items():
            for name in ('ic_launcher', 'ic_launcher_round'):
                with Image.open(io.BytesIO(self.outputs[f'mipmap-{density}/{name}.png'])) as image:
                    self.assertEqual(image.size, (size, size))
                    self.assertEqual(image.mode, 'RGBA')

    def test_vector_preserves_uniform_scale_and_fits_adaptive_safe_circle(self):
        width, height, _, _ = artwork()
        node = ET.fromstring(self.outputs['drawable/ic_launcher_foreground.xml'])
        group = node.find('group')
        key = lambda name: f'{{{ANDROID}}}{name}'
        sx, sy = float(group.get(key('scaleX'))), float(group.get(key('scaleY')))
        self.assertEqual(sx, sy)
        self.assertAlmostEqual(math.hypot(width * sx, height * sy), SAFE_DIAMETER, places=5)
        self.assertAlmostEqual(float(group.get(key('translateX'))) + width * sx / 2, 54, places=5)
        self.assertAlmostEqual(float(group.get(key('translateY'))) + height * sy / 2, 54, places=5)

    def test_notification_and_themed_icon_use_white_paths_without_background(self):
        for name in ('ic_stat_sillytavern', 'ic_launcher_monochrome'):
            node = ET.fromstring(self.outputs[f'drawable/{name}.xml'])
            paths = list(node.iter('path'))
            self.assertEqual(len(paths), 2)
            self.assertTrue(all(p.get(f'{{{ANDROID}}}fillColor') == '#FFFFFFFF' for p in paths))
            self.assertIsNone(node.find('background'))

    def test_round_legacy_has_transparent_corners_and_source_colors_remain(self):
        for density in DENSITIES:
            image = Image.open(io.BytesIO(self.outputs[f'mipmap-{density}/ic_launcher_round.png']))
            self.assertEqual(image.getpixel((0, 0))[3], 0)
        image = Image.open(io.BytesIO(self.outputs['mipmap-xxxhdpi/ic_launcher.png']))
        pixels = list(image.get_flattened_data())
        self.assertTrue(any(r > g * 2 and r > b * 2 and a > 200 for r, g, b, a in pixels))
        self.assertTrue(any(min(r, g, b) > 240 and a > 200 for r, g, b, a in pixels))

    def test_checked_in_assets_and_source_are_unchanged(self):
        self.assertEqual(sha(SOURCE.read_bytes()), SOURCE_HASH)
        for name, data in self.outputs.items():
            self.assertEqual((RES / name).read_bytes(), data, name)

    def test_launcher_copy_contains_no_disclaimer_labels(self):
        blocked = re.compile(r'实验版|实验功能|未经测试|仍待.{0,8}测|勿作唯一|唯一数据副本|不保证|未完成M[0-9]|实验边界')
        files = list((ROOT / 'android-app/app/src/main').rglob('*.kt'))
        files += list((ROOT / 'android-app/app/src/main').rglob('*.xml'))
        for file in files:
            self.assertIsNone(blocked.search(file.read_text()), str(file))


if __name__ == '__main__':
    unittest.main()
