#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Advance android-app/version.properties for an automated build (stdlib only)."""
import argparse
import os
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
VERSION_FILE = ROOT / 'android-app/version.properties'
SEMVER = re.compile(r'^(\d+)\.(\d+)\.(\d+)$')
KEYS = ('versionCode', 'versionName')


def definition(key):
    """One regex shared by reading and writing, so both agree on what a definition is."""
    return re.compile(rf'(?m)^[ \t]*{re.escape(key)}[ \t]*=[^\n]*$')


def parse(text):
    """Parse with the same rule used for replacement; reject duplicate keys."""
    values = {}
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith('#'):
            continue
        key, separator, value = stripped.partition('=')
        if not separator:
            raise ValueError(f'Malformed line in version.properties: {line}')
        key = key.strip()
        if key in values:
            raise ValueError(f'Duplicate {key} definition in version.properties')
        values[key] = value.strip()
    for key in KEYS:
        if key not in values:
            raise ValueError(f'Missing {key} in version.properties')
        if len(definition(key).findall(text)) != 1:
            raise ValueError(f'{key}: expected exactly one definition in version.properties')
    if not values['versionCode'].isdigit():
        raise ValueError(f"versionCode must be a positive integer, got {values['versionCode']}")
    return int(values['versionCode']), values['versionName']


def read():
    return parse(VERSION_FILE.read_text())


def next_name(current, bump):
    match = SEMVER.match(current)
    if not match:
        raise ValueError(f'versionName {current} is not MAJOR.MINOR.PATCH; pass --version-name explicitly')
    major, minor, patch = (int(part) for part in match.groups())
    if bump == 'major':
        return f'{major + 1}.0.0'
    if bump == 'minor':
        return f'{major}.{minor + 1}.0'
    return f'{major}.{minor}.{patch + 1}'


def replace_property(text, key, value):
    """Normalize the definition to `key=value`; refuse anything but a single match."""
    updated, count = definition(key).subn(lambda _: f'{key}={value}', text)
    if count != 1:
        raise ValueError(f'{key}: expected one definition to replace, got {count}')
    return updated


def write(code, name):
    text = VERSION_FILE.read_text()
    for key, value in (('versionCode', code), ('versionName', name)):
        text = replace_property(text, key, value)
    # Both fields validated before the file is touched; publish atomically.
    temporary = VERSION_FILE.with_name(f'.{VERSION_FILE.name}.{os.getpid()}.tmp')
    try:
        temporary.write_text(text)
        temporary.replace(VERSION_FILE)
    finally:
        if temporary.exists():
            temporary.unlink()
    # Read back so "reported" and "on disk" can never diverge.
    written_code, written_name = read()
    if (written_code, written_name) != (int(code), str(name)):
        raise ValueError(f'Version write-back mismatch: wanted {code}/{name}, file now has {written_code}/{written_name}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bump', choices=['major', 'minor', 'patch', 'none'], default='patch')
    parser.add_argument('--version-name', help='use this exact versionName instead of computing one')
    parser.add_argument('--dry-run', action='store_true', help='report the next version without writing')
    args = parser.parse_args()
    code, name = read()
    if args.version_name:
        if not SEMVER.match(args.version_name):
            raise ValueError(f'--version-name must be MAJOR.MINOR.PATCH, got {args.version_name}')
        new_name, new_code = args.version_name, code + 1
    elif args.bump == 'none':
        new_name, new_code = name, code
    else:
        new_name, new_code = next_name(name, args.bump), code + 1
    changed = (new_name, new_code) != (name, code)
    if changed and not args.dry_run:
        write(new_code, new_name)
    # Key=value lines so CI can append them straight to $GITHUB_OUTPUT.
    lines = [f'versionName={new_name}', f'versionCode={new_code}',
             f'previousVersionName={name}', f'previousVersionCode={code}',
             f'changed={"true" if changed else "false"}']
    print('\n'.join(lines))
    output = os.environ.get('GITHUB_OUTPUT')
    if output:
        with open(output, 'a', encoding='utf-8') as stream:
            stream.write('\n'.join(lines) + '\n')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError) as error:
        sys.exit(f'Version bump failed: {error}')
