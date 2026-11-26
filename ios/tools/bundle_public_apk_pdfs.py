#!/usr/bin/env python3
"""Re-use verified public Android release PDFs in an iOS preview, without Drive secrets."""
import hashlib
import json
from pathlib import Path
import re
import sys
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/learning_import'))
from bundle_note_pdfs import bundle, MAX_TOTAL
from verify_pdf_bundle import verify

# Published signed Android 3.0.8, approved for public distribution including PDFs.
EXPECTED_SHA256 = '1538685cd65538f5147965565da10efdbd5dfb6c7cdff9a355725617fa9b5f3c'
PREFIX = 'assets/private/note_pdfs/'
MAX_APK = 150 * 1024 * 1024


def extract(apk, catalog, output, expected_hash=EXPECTED_SHA256):
    apk = Path(apk)
    if apk.stat().st_size > MAX_APK: raise ValueError('APK ist zu groß')
    digest = hashlib.sha256()
    with apk.open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''): digest.update(chunk)
    if digest.hexdigest() != expected_hash: raise ValueError('APK-Prüfsumme stimmt nicht')
    with ZipFile(apk) as z:
        entries = [entry for entry in z.infolist() if entry.filename.startswith(PREFIX)]
        names = [entry.filename[len(PREFIX):] for entry in entries]
        if len(names) != len(set(names)) or 'manifest.json' not in names:
            raise ValueError('Ungültiges PDF-Verzeichnis')
        pdfs = [entry for entry in entries if entry.filename != PREFIX + 'manifest.json']
        if not pdfs or any(not re.fullmatch(r'[A-Za-z0-9_]+\.pdf', x.filename[len(PREFIX):]) for x in pdfs):
            raise ValueError('Ungültiger PDF-Dateiname')
        if sum(x.file_size for x in pdfs) > MAX_TOTAL: raise ValueError('PDF-Sammlung zu groß')
        files = {x.filename[len(PREFIX):]: z.read(x) for x in pdfs}
    indexed = {name: {'name': name} for name in files}
    # Re-extract each PDF and check it against all 42 current catalog entries.
    count, size = bundle(catalog, indexed, lambda entry: files[entry['name']], output)
    # Also compare every file and the generated manifest against the trusted APK.
    if verify(apk, output) != count: raise ValueError('PDF-Manifest stimmt nicht überein')
    return count, size


if __name__ == '__main__':
    try:
        catalog = json.loads(Path(sys.argv[2]).read_text(encoding='utf-8'))
        count, size = extract(sys.argv[1], catalog, sys.argv[3])
        print(f'Passende öffentliche Original-PDFs eingebunden: {count}; Gesamtgröße {size} Bytes.')
    except Exception as exc:
        print('::error title=Offline-PDFs::Öffentliche Original-PDFs konnten nicht vollständig und passend geprüft werden.', file=sys.stderr)
        raise
