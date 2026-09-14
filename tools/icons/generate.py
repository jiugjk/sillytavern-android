#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Generate launcher, adaptive and notification artwork from ST's existing SVG."""
import argparse
import hashlib
import io
import json
import math
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'build/icon-tools'))
import cairosvg
from PIL import Image, ImageDraw

SVG = 'http://www.w3.org/2000/svg'
ANDROID = 'http://schemas.android.com/apk/res/android'
ET.register_namespace('android', ANDROID)
ET.register_namespace('', SVG)
SOURCE = ROOT / 'upstream/SillyTavern/public/img/logo.svg'
SOURCE_HASH = 'f781779749f4903d20380a1d957b0bad679e57c76c392ee83282bda844d5dba0'
BACKGROUND_SOURCE = ROOT / 'upstream/SillyTavern/public/img/apple-icon-512x512.png'
BACKGROUND_HASH = 'd0c1dbe0925f5197e33dc84019ec327fc5af3d4d1fbe9a1f73633993c91c1e45'
RES = ROOT / 'android-app/app/src/main/res'
DENSITIES = {'ldpi': 36, 'mdpi': 48, 'tvdpi': 64, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}
SAFE_DIAMETER = 66.0


def sha(data):
    return hashlib.sha256(data).hexdigest()


def xml_bytes(element):
    ET.indent(element, space='    ')
    return b'<?xml version="1.0" encoding="utf-8"?>\n' + ET.tostring(element, encoding='utf-8') + b'\n'


def artwork():
    data = SOURCE.read_bytes()
    if sha(data) != SOURCE_HASH or sha(BACKGROUND_SOURCE.read_bytes()) != BACKGROUND_HASH:
        raise ValueError('Upstream icon source changed; review the new artwork before generating')
    root = ET.fromstring(data)
    x, y, width, height = map(float, root.attrib['viewBox'].split())
    if x or y:
        raise ValueError('Expected zero-based ST viewBox')
    paths = []
    def visit(element, tx=0.0, ty=0.0):
        transform = element.get('transform', '')
        if transform:
            match = re.fullmatch(r'translate\(\s*([+-]?[\d.]+)[,\s]+([+-]?[\d.]+)\s*\)', transform)
            if not match:
                raise ValueError('Unrecognized source transform; do not approximate it')
            tx += float(match[1]); ty += float(match[2])
        if element.tag == f'{{{SVG}}}path':
            paths.append((element.get('d'), element.get('fill'), tx, ty))
        for child in element:
            visit(child, tx, ty)
    visit(root)
    if len(paths) != 2 or not all(path[0] and path[1] for path in paths):
        raise ValueError('Unrecognized ST logo paths')
    with Image.open(BACKGROUND_SOURCE) as image:
        pixel = image.convert('RGB').getpixel((0, 0))
    background = '#' + ''.join(f'{channel:02X}' for channel in pixel)
    return width, height, paths, background


def geometry(width, height, viewport, scale):
    return (viewport - width * scale) / 2, (viewport - height * scale) / 2


def vector(width, height, paths, viewport, scale, mono=False):
    attr = lambda key: f'{{{ANDROID}}}{key}'
    node = ET.Element('vector', {attr('width'): f'{viewport}dp', attr('height'): f'{viewport}dp',
        attr('viewportWidth'): str(viewport), attr('viewportHeight'): str(viewport)})
    tx, ty = geometry(width, height, viewport, scale)
    outer = ET.SubElement(node, 'group', {attr('scaleX'): f'{scale:.9f}', attr('scaleY'): f'{scale:.9f}',
        attr('translateX'): f'{tx:.9f}', attr('translateY'): f'{ty:.9f}'})
    ET.SubElement(outer, 'clip-path', {attr('pathData'): f'M0,0 L{width},0 L{width},{height} L0,{height} Z'})
    for data, color, dx, dy in paths:
        group = ET.SubElement(outer, 'group', {attr('translateX'): f'{dx:.9f}', attr('translateY'): f'{dy:.9f}'})
        raw = color.removeprefix('#')
        if len(raw) == 3:
            raw = ''.join(ch * 2 for ch in raw)
        ET.SubElement(group, 'path', {attr('fillColor'): '#FFFFFFFF' if mono else '#FF' + raw.upper(), attr('pathData'): data})
    return xml_bytes(node)


def svg_image(width, height, paths, background=None, mono=False, notification=False):
    viewport = 24 if notification else 108
    scale = 22 / width if notification else SAFE_DIAMETER / math.hypot(width, height)
    tx, ty = geometry(width, height, viewport, scale)
    # Android exposes the central 72dp of a 108dp adaptive layer. The legacy
    # bitmap uses exactly that same framing rather than stretching the logo.
    box = '0 0 24 24' if notification else '18 18 72 72'
    node = ET.Element(f'{{{SVG}}}svg', {'viewBox': box, 'width': str(viewport), 'height': str(viewport)})
    if background:
        ET.SubElement(node, f'{{{SVG}}}rect', {'width': str(viewport), 'height': str(viewport), 'fill': background})
    outer = ET.SubElement(node, f'{{{SVG}}}g', {'transform': f'translate({tx} {ty}) scale({scale})'})
    defs = ET.SubElement(outer, f'{{{SVG}}}defs')
    clip = ET.SubElement(defs, f'{{{SVG}}}clipPath', {'id': 'source-viewport'})
    ET.SubElement(clip, f'{{{SVG}}}rect', {'width': str(width), 'height': str(height)})
    content = ET.SubElement(outer, f'{{{SVG}}}g', {'clip-path': 'url(#source-viewport)'})
    for data, color, dx, dy in paths:
        ET.SubElement(content, f'{{{SVG}}}path', {'d': data, 'fill': '#FFFFFF' if mono else color, 'transform': f'translate({dx} {dy})'})
    return ET.tostring(node, encoding='utf-8')


def raster(svg, size, rounded=False):
    pixels = cairosvg.svg2png(bytestring=svg, output_width=size * 4, output_height=size * 4)
    image = Image.open(io.BytesIO(pixels)).convert('RGBA')
    if rounded:
        mask = Image.new('L', image.size)
        ImageDraw.Draw(mask).ellipse((0, 0, image.width - 1, image.height - 1), fill=255)
        image.putalpha(mask)
    image = image.resize((size, size), Image.Resampling.LANCZOS)
    buffer = io.BytesIO(); image.save(buffer, format='PNG', optimize=True)
    return buffer.getvalue()


def adaptive(mono=False):
    node = ET.Element('adaptive-icon')
    ET.SubElement(node, 'background', {f'{{{ANDROID}}}drawable': '@color/ic_launcher_background'})
    ET.SubElement(node, 'foreground', {f'{{{ANDROID}}}drawable': '@drawable/ic_launcher_foreground'})
    if mono:
        ET.SubElement(node, 'monochrome', {f'{{{ANDROID}}}drawable': '@drawable/ic_launcher_monochrome'})
    return xml_bytes(node)


def generated():
    width, height, paths, background = artwork()
    scale = SAFE_DIAMETER / math.hypot(width, height)
    values = ET.Element('resources')
    ET.SubElement(values, 'string', {'name': 'app_name'}).text = 'SillyTavern'
    ET.SubElement(values, 'color', {'name': 'ic_launcher_background'}).text = background
    result = {
        'values/branding.xml': xml_bytes(values),
        'drawable/ic_launcher_foreground.xml': vector(width, height, paths, 108, scale),
        'drawable/ic_launcher_monochrome.xml': vector(width, height, paths, 108, scale, mono=True),
        'drawable/ic_stat_sillytavern.xml': vector(width, height, paths, 24, 22 / width, mono=True),
    }
    full = svg_image(width, height, paths, background)
    for density, size in DENSITIES.items():
        result[f'mipmap-{density}/ic_launcher.png'] = raster(full, size)
        result[f'mipmap-{density}/ic_launcher_round.png'] = raster(full, size, rounded=True)
    for version in (26, 33):
        for name in ('ic_launcher', 'ic_launcher_round'):
            result[f'mipmap-anydpi-v{version}/{name}.xml'] = adaptive(mono=version >= 33)
    return result


def preview(destination):
    width, height, paths, background = artwork()
    image = Image.new('RGB', (1000, 630), '#171717')
    draw = ImageDraw.Draw(image)
    draw.text((22, 15), 'ST SVG: square / circle / rounded mask', fill='white')
    base = Image.open(io.BytesIO(raster(svg_image(width, height, paths, background), 240)))
    for index, shape in enumerate(['square', 'circle', 'rounded']):
        icon = base.copy()
        if shape != 'square':
            mask = Image.new('L', icon.size); pen = ImageDraw.Draw(mask)
            if shape == 'circle': pen.ellipse((0, 0, 239, 239), fill=255)
            else: pen.rounded_rectangle((0, 0, 239, 239), radius=65, fill=255)
            icon.putalpha(mask)
        image.paste(icon, (22 + index * 325, 45), icon)
        draw.text((22 + index * 325, 295), shape, fill='white')
    draw.text((22, 335), 'Legacy dpi sizes: ldpi / mdpi / tvdpi / hdpi / xhdpi / xxhdpi / xxxhdpi', fill='white')
    left = 22
    for density, size in DENSITIES.items():
        icon = Image.open(io.BytesIO(raster(svg_image(width, height, paths, background), size)))
        image.paste(icon, (left, 370), icon)
        draw.text((left, 575), f'{density}: {size}', fill='white')
        left += size + 22
    draw.text((790, 335), 'Notification alpha', fill='white')
    for i, size in enumerate([24, 48, 72]):
        icon = Image.open(io.BytesIO(raster(svg_image(width, height, paths, mono=True, notification=True), size)))
        image.paste(icon, (810, 365 + i * 83), icon)
    destination.parent.mkdir(parents=True, exist_ok=True)
    image.save(destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--preview', type=Path)
    args = parser.parse_args()
    outputs = generated()
    for name, data in outputs.items():
        path = RES / name
        if args.check:
            if not path.is_file() or path.read_bytes() != data:
                raise ValueError(f'Icon differs from generated source: {name}')
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
    provenance = {
        'schemaVersion': 1,
        'upstreamCommit': 'c0a417b240c640037bab69529aa1e0c466c69397',
        'sources': {
            SOURCE.relative_to(ROOT).as_posix(): SOURCE_HASH,
            BACKGROUND_SOURCE.relative_to(ROOT).as_posix(): BACKGROUND_HASH,
        },
        'densities': DENSITIES,
        'adaptiveViewport': 108,
        'safeDiameter': SAFE_DIAMETER,
        'files': {name: sha(data) for name, data in sorted(outputs.items())},
    }
    metadata = (json.dumps(provenance, indent=2) + '\n').encode()
    manifest = ROOT / 'tools/icons/manifest.json'
    if args.check:
        if not manifest.is_file() or manifest.read_bytes() != metadata:
            raise ValueError('Icon provenance manifest differs from generated assets')
    else:
        manifest.write_bytes(metadata)
    if args.preview:
        preview(args.preview)
    print(f'{"Verified" if args.check else "Generated"} {len(outputs)} branding resources from {SOURCE.relative_to(ROOT)}')


if __name__ == '__main__':
    main()
