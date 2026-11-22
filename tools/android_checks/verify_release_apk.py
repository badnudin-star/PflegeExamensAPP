#!/usr/bin/env python3
"""Check the offline catalog inside a built APK and print its SHA-256."""
import hashlib
from pathlib import Path
import sys
import zipfile


def verify(apk, catalog):
    expected = Path(catalog).read_bytes()
    with zipfile.ZipFile(apk) as archive:
        if archive.read('assets/learning_content.json') != expected:
            raise ValueError('Offline-Katalog in der APK stimmt nicht überein.')
    digest = hashlib.sha256()
    with Path(apk).open('rb') as file:
        for block in iter(lambda: file.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def main():
    try:
        if len(sys.argv) != 3: raise ValueError('Eingaben fehlen.')
        digest = verify(sys.argv[1], sys.argv[2])
    except (OSError, ValueError, KeyError, zipfile.BadZipFile):
        print('::error::Release-APK oder Offline-Katalog konnte nicht geprüft werden.')
        return 1
    print('APK-SHA-256: ' + digest)
    return 0


if __name__ == '__main__': sys.exit(main())
