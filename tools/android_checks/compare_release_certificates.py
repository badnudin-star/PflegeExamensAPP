#!/usr/bin/env python3
"""Compare public SHA-256 signing certificates from two apksigner reports."""
from pathlib import Path
import re
import sys

PATTERN = re.compile(r'^Signer #(\d+) certificate SHA-256 digest: ([0-9a-fA-F]{64})$', re.MULTILINE)

def compare(previous, current):
    old = PATTERN.findall(Path(previous).read_text(encoding='utf-8'))
    new = PATTERN.findall(Path(current).read_text(encoding='utf-8'))
    if len(old) != 1 or len(new) != 1 or old[0][0] != '1' or new[0][0] != '1' or old[0][1].lower() != new[0][1].lower():
        raise ValueError('Signierzertifikat stimmt nicht eindeutig überein')

def main():
    try:
        if len(sys.argv) != 3: raise ValueError('Eingaben fehlen')
        compare(sys.argv[1], sys.argv[2])
    except (OSError, ValueError):
        print('::error::Update-Zertifikat stimmt nicht überein oder kann nicht geprüft werden. APK nicht verteilen.')
        return 1
    print('Signierzertifikat stimmt mit der vorherigen internen APK überein.')
    return 0

if __name__ == '__main__': sys.exit(main())
