#!/usr/bin/env python3
"""Fingerprint the exact probe/test implementation packaged in this APK."""
import argparse
import json
from pathlib import Path
from runtime_common import probe_manifest

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(probe_manifest(), indent=2) + '\n')
