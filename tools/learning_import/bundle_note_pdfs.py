#!/usr/bin/env python3
"""Bundle matching Drive PDFs for an offline internal APK; never publish to Drive."""
import hashlib
import json
import os
from pathlib import Path
import re
import sys

from audit_notes import read_pdf
from import_notes import TOPICS, extract_pdf, list_folder, topic_files

MAX_TOTAL = 32 * 1024 * 1024


def bundle(catalog, indexed, get_pdf, output):
    notes = {note['topic']: note for note in catalog['notes']}
    if len(notes) != len(TOPICS) or set(notes) != {title for _, title in TOPICS}:
        raise ValueError('catalog topics incomplete')
    pairs = [topic_files(indexed, i) for i in range(len(TOPICS))]
    names = list(dict.fromkeys(name for pair in pairs for name in pair))
    if any(not re.fullmatch(r'[A-Za-z0-9_]+\.pdf', name) for name in names):
        raise ValueError('invalid mapped filename')
    files, texts, size = {}, {}, 0
    for name in names:
        data = get_pdf(indexed[name])
        size += len(data)
        if size > MAX_TOTAL: raise ValueError('PDF bundle too large')
        text = extract_pdf(data).strip()
        if not text: raise ValueError('no extractable text')
        files[name] = data
        texts[name] = text
    for (_, title), (main, simple) in zip(TOPICS, pairs, strict=True):
        note = notes[title]
        if (note.get('formatVersion') != 2 or note['detailed'] != texts[main] or
            note['balanced'] != texts[main] or note['short'] != texts[simple] or
            note['sources'] != ['Lernzettel: ' + main, 'Lernzettel: ' + simple]):
            raise ValueError('PDF text differs from bundled catalog')
    output = Path(output)
    if output.exists(): raise ValueError('output already exists')
    output.mkdir(parents=True, mode=0o700)
    for name, data in files.items():
        (output / name).write_bytes(data)
    manifest = {name: hashlib.sha256(text.encode('utf-8')).hexdigest() for name, text in texts.items()}
    (output / 'manifest.json').write_text(json.dumps(manifest, sort_keys=True, separators=(',', ':')), encoding='utf-8')
    return len(files), size


def main():
    folder = os.environ.get('LEARNING_DRIVE_FOLDER_ID', '')
    if not re.fullmatch(r'[A-Za-z0-9_-]+', folder): raise ValueError('invalid folder')
    import google.auth
    from google.auth.transport.requests import AuthorizedSession
    credentials, _ = google.auth.default(scopes=['https://www.googleapis.com/auth/drive.readonly'])
    session = AuthorizedSession(credentials)
    before = list_folder(session, folder)
    catalog = json.loads(Path('app/src/main/assets/learning_content.json').read_text(encoding='utf-8'))
    count, size = bundle(catalog, before, lambda f: read_pdf(session, f), 'app/src/main/assets/private/note_pdfs')
    if before != list_folder(session, folder): raise ValueError('source changed during download')
    print(f'Offline-PDFs: {count}, insgesamt {size} Bytes; Quelltexte stimmen mit dem Offline-Katalog überein.')


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('::error title=Offline-PDFs::Einbindung fehlgeschlagen; keine Inhalte oder Zugangsdaten ausgeben.', file=sys.stderr)
        sys.exit(1)
