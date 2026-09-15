#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Verify the ST artwork provenance and Android icon resource graph using stdlib.

This runs as a build gate, so it must not depend on `assert`: python -O strips
assert statements and the script would still print PASS. Every check raises.
"""
import hashlib
import json
from pathlib import Path
import struct
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'android-app/app/src/main/res'
ANDROID = '{http://schemas.android.com/apk/res/android}'


def check(condition, message):
    if not condition:
        raise ValueError(message)


def required(node, message):
    """Same explicit gate, but returns the element so callers can keep chaining."""
    if node is None:
        raise ValueError(message)
    return node


def verify():
    metadata = json.loads((ROOT / 'tools/icons/manifest.json').read_text())
    for name, wanted in metadata['sources'].items():
        digest = hashlib.sha256((ROOT / name).read_bytes()).hexdigest()
        check(digest == wanted, f'Source artwork hash mismatch: {name}')
    for name, wanted in metadata['files'].items():
        data = (RES / name).read_bytes()
        check(hashlib.sha256(data).hexdigest() == wanted, f'Generated resource hash mismatch: {name}')
        if name.endswith('.png'):
            check(data.startswith(b'\x89PNG\r\n\x1a\n'), f'Not a PNG: {name}')
            density = name.split('/')[0].removeprefix('mipmap-')
            check(struct.unpack('>II', data[16:24]) == (metadata['densities'][density],) * 2,
                  f'Icon dimensions mismatch: {name}')
    app = required(ET.parse(ROOT / 'android-app/app/src/main/AndroidManifest.xml').getroot().find('application'),
                   'AndroidManifest.xml has no <application> element')
    check(app.get(ANDROID + 'icon') == '@mipmap/ic_launcher', 'Unexpected application icon reference')
    check(app.get(ANDROID + 'roundIcon') == '@mipmap/ic_launcher_round', 'Unexpected application roundIcon reference')
    check(app.get(ANDROID + 'label') == '@string/app_name', 'Unexpected application label reference')
    for version in (26, 33):
        for name in ('ic_launcher', 'ic_launcher_round'):
            icon = ET.parse(RES / f'mipmap-anydpi-v{version}/{name}.xml').getroot()
            for layer, drawable in (('background', '@color/ic_launcher_background'),
                                    ('foreground', '@drawable/ic_launcher_foreground')):
                node = icon.find(layer)
                check(node is not None and node.get(ANDROID + 'drawable') == drawable,
                      f'Adaptive icon v{version}/{name} has an unexpected {layer}')
            if version >= 33:
                monochrome = icon.find('monochrome')
                check(monochrome is not None and monochrome.get(ANDROID + 'drawable') == '@drawable/ic_launcher_monochrome',
                      f'Adaptive icon v{version}/{name} has an unexpected monochrome layer')
    for name in ('ic_launcher_foreground', 'ic_launcher_monochrome', 'ic_stat_sillytavern'):
        node = ET.parse(RES / f'drawable/{name}.xml').getroot()
        group = required(node.find('group'), f'Vector drawable has no <group>: {name}')
        check(group.get(ANDROID + 'scaleX') == group.get(ANDROID + 'scaleY'), f'Non-uniform vector scale: {name}')
        if name != 'ic_launcher_foreground':
            check(all(p.get(ANDROID + 'fillColor') == '#FFFFFFFF' for p in node.iter('path')),
                  f'Notification/monochrome drawable must stay single-colour: {name}')
    print('PASS: ST source hashes, 7 density variants, adaptive/monochrome resources and manifest icon references')


if __name__ == '__main__':
    try:
        verify()
    except (ValueError, OSError, KeyError, ET.ParseError) as error:
        sys.exit(f'Icon verification failed: {error}')
