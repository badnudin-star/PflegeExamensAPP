#!/usr/bin/env python3
"""Compare public SHA-256 signing certificates from two apksigner reports."""
from pathlib import Path
import re
import sys

PATTERN = re.compile(r'^Signer #(\d+) certificate SHA-256 digest: ([0-9a-fA-F]{64})$', re.MULTILINE)

def compare(previous, current):
    old = PATTERN.findall(Path(previous).read_text(encoding='utf-8'))
    new = PATTERN.findall(Path(current).read_text(encoding='utf-8'))
    if len(old) != 1 or len(new) != 1 or old[0][0] != '1' or new[0][0] != '1':
        raise ValueError(f'Signaturausgabe nicht eindeutig: alt={len(old)}, neu={len(new)}')
    # Certificate fingerprints are public, unlike signing keys or passwords.
    print('::notice title=Vorheriges öffentliches Signierzertifikat::' + old[0][1].lower())
    print('::notice title=Neues öffentliches Signierzertifikat::' + new[0][1].lower())
    if old[0][1].lower() != new[0][1].lower():
        raise ValueError('Signierzertifikate unterscheiden sich')

def main():
    try:
        if len(sys.argv) != 3: raise ValueError('Eingaben fehlen')
        compare(sys.argv[1], sys.argv[2])
    except ValueError as exc:
        print(f'::error::Update-Prüfung: {exc}. APK nicht verteilen.')
        return 1
    except OSError:
        print('::error::Update-Zertifikat kann nicht geprüft werden. APK nicht verteilen.')
        return 1
    print('Signierzertifikat stimmt mit der vorherigen internen APK überein.')
    return 0

if __name__ == '__main__': sys.exit(main())
