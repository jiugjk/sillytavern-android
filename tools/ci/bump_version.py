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


def read():
    values = {}
    for line in VERSION_FILE.read_text().splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith('#'):
            continue
        key, separator, value = stripped.partition('=')
        if not separator:
            raise ValueError(f'Malformed line in version.properties: {line}')
        values[key.strip()] = value.strip()
    for key in ('versionCode', 'versionName'):
        if key not in values:
            raise ValueError(f'Missing {key} in version.properties')
    if not values['versionCode'].isdigit():
        raise ValueError(f"versionCode must be a positive integer, got {values['versionCode']}")
    return int(values['versionCode']), values['versionName']


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


def write(code, name):
    text = VERSION_FILE.read_text()
    text = re.sub(r'^versionCode=.*$', f'versionCode={code}', text, count=1, flags=re.MULTILINE)
    text = re.sub(r'^versionName=.*$', f'versionName={name}', text, count=1, flags=re.MULTILINE)
    VERSION_FILE.write_text(text)


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
