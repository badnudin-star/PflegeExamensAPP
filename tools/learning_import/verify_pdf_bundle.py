#!/usr/bin/env python3
"""Verify all generated PDFs and their manifest were packaged unchanged in an APK."""
import json
from pathlib import Path
import re
import sys
from zipfile import ZipFile


def verify(apk, directory):
    directory = Path(directory)
    manifest_data = (directory / 'manifest.json').read_bytes()
    manifest = json.loads(manifest_data)
    if not isinstance(manifest, dict) or len(manifest) < 1:
        raise ValueError('empty manifest')
    if any(not re.fullmatch(r'[A-Za-z0-9_]+\.pdf', name) or not isinstance(digest, str) or
           not re.fullmatch(r'[0-9a-f]{64}', digest) for name, digest in manifest.items()):
        raise ValueError('invalid manifest')
    expected = set(manifest) | {'manifest.json'}
    if {p.name for p in directory.iterdir()} != expected: raise ValueError('unexpected bundle files')
    prefix = 'assets/private/note_pdfs/'
    with ZipFile(apk) as archive:
        entries = [name for name in archive.namelist() if name.startswith(prefix)]
        if len(entries) != len(expected) or {name[len(prefix):] for name in entries} != expected:
            raise ValueError('APK bundle entries differ')
        for name in expected:
            if archive.read(prefix + name) != (directory / name).read_bytes():
                raise ValueError('APK bundle content differs')
    return len(manifest)


if __name__ == '__main__':
    try:
        print(f'Offline-PDFs in APK geprüft: {verify(sys.argv[1], sys.argv[2])}')
    except Exception:
        print('::error title=Offline-PDFs::APK-PDF-Prüfung fehlgeschlagen.')
        sys.exit(1)
