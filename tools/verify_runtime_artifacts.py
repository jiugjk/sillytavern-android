#!/usr/bin/env python3
"""Static checks only: this command cannot certify Android execution."""
import subprocess
import sys
from runtime_common import ROOT, read_lock, sdk_paths, verify_artifacts

if __name__ == '__main__':
    try:
        lock = read_lock()
        _, tc, _ = sdk_paths(lock)
        verify_artifacts(ROOT / 'build/runtime', lock, tc / 'bin/llvm-readelf')
        print('PASS: pinned arm64 runtime artifacts, hashes, symbols and 16 KB ELF alignment (static only)')
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError) as error:
        sys.exit(f'Runtime artifact verification failed: {error}')
