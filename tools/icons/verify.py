#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Verify the ST artwork provenance and Android icon resource graph using stdlib."""
import hashlib
import json
from pathlib import Path
import struct
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'android-app/app/src/main/res'
ANDROID = '{http://schemas.android.com/apk/res/android}'


def verify():
    metadata = json.loads((ROOT / 'tools/icons/manifest.json').read_text())
    for name, wanted in metadata['sources'].items():
        assert hashlib.sha256((ROOT / name).read_bytes()).hexdigest() == wanted, name
    for name, wanted in metadata['files'].items():
        data = (RES / name).read_bytes()
        assert hashlib.sha256(data).hexdigest() == wanted, name
        if name.endswith('.png'):
            assert data.startswith(b'\x89PNG\r\n\x1a\n')
            density = name.split('/')[0].removeprefix('mipmap-')
            assert struct.unpack('>II', data[16:24]) == (metadata['densities'][density],) * 2, name
    app = ET.parse(ROOT / 'android-app/app/src/main/AndroidManifest.xml').getroot().find('application')
    assert app.get(ANDROID + 'icon') == '@mipmap/ic_launcher'
    assert app.get(ANDROID + 'roundIcon') == '@mipmap/ic_launcher_round'
    assert app.get(ANDROID + 'label') == '@string/app_name'
    for version in (26, 33):
        for name in ('ic_launcher', 'ic_launcher_round'):
            icon = ET.parse(RES / f'mipmap-anydpi-v{version}/{name}.xml').getroot()
            assert icon.find('background').get(ANDROID + 'drawable') == '@color/ic_launcher_background'
            assert icon.find('foreground').get(ANDROID + 'drawable') == '@drawable/ic_launcher_foreground'
            if version >= 33:
                assert icon.find('monochrome').get(ANDROID + 'drawable') == '@drawable/ic_launcher_monochrome'
    for name in ('ic_launcher_foreground', 'ic_launcher_monochrome', 'ic_stat_sillytavern'):
        node = ET.parse(RES / f'drawable/{name}.xml').getroot()
        group = node.find('group')
        assert group.get(ANDROID + 'scaleX') == group.get(ANDROID + 'scaleY'), name
        if name != 'ic_launcher_foreground':
            assert all(p.get(ANDROID + 'fillColor') == '#FFFFFFFF' for p in node.iter('path')), name
    print('PASS: ST source hashes, 7 density variants, adaptive/monochrome resources and manifest icon references')


if __name__ == '__main__':
    verify()
