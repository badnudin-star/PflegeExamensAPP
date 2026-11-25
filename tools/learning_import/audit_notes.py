#!/usr/bin/env python3
"""Read-only audit of Drive PDFs against the live catalog. Never prints source text."""
import hashlib
import io
import os
import re
import sys
from urllib.parse import quote

from import_notes import TOPICS, MAX_PDF, extract_pdf, list_folder, topic_files
from sync_drive import origin, read_remote


def read_pdf(session, file):
    response = session.get('https://www.googleapis.com/drive/v3/files/' + quote(file['id'], safe=''),
                           params={'alt': 'media', 'supportsAllDrives': 'true'}, timeout=60, stream=True)
    response.raise_for_status()
    content = bytearray()
    try:
        for chunk in response.iter_content(65536):
            content.extend(chunk)
            if len(content) > MAX_PDF:
                raise ValueError('PDF too large')
    finally:
        response.close()
    if len(content) != int(file['size']) or hashlib.md5(content).hexdigest() != file.get('md5Checksum'):
        raise ValueError('PDF changed during download')
    return bytes(content)


def inspect_pdf(content):
    from pypdf import PdfReader
    pdf = PdfReader(io.BytesIO(content), strict=True)
    if pdf.is_encrypted or not 1 <= len(pdf.pages) <= 100:
        raise ValueError('invalid PDF')
    empty_pages = []
    image_pages = []
    images = 0
    for number, page in enumerate(pdf.pages, 1):
        if not (page.extract_text() or '').strip(): empty_pages.append(number)
        # Includes pypdf-discoverable XObject and inline images, but does not
        # establish whether charts are vector drawings, legible or meaningful.
        count = len(page.images)
        if count: image_pages.append(number)
        images += count
    return {'pages': len(pdf.pages), 'empty_pages': empty_pages, 'image_pages': image_pages, 'images': images}


def audit(catalog, indexed, get_pdf):
    notes = {note['topic']: note for note in catalog['notes']}
    if len(notes) != len(TOPICS) or set(notes) != {title for _, title in TOPICS}:
        raise ValueError('catalog topics incomplete')
    pairs = [topic_files(indexed, i) for i in range(len(TOPICS))]
    source = {}
    for name in dict.fromkeys(n for pair in pairs for n in pair):
        data = get_pdf(indexed[name])
        source[name] = (extract_pdf(data).strip(), inspect_pdf(data))
        if not source[name][0]: raise ValueError('PDF has no extractable text')
    mismatches = []
    for (_, title), (main, simple) in zip(TOPICS, pairs, strict=True):
        note = notes[title]
        if (note.get('formatVersion') != 2 or note['detailed'] != source[main][0] or
                note['balanced'] != source[main][0] or note['short'] != source[simple][0] or
                note['sources'] != ['Lernzettel: ' + main, 'Lernzettel: ' + simple]):
            mismatches.append(title)
    image_files = sum(stats['images'] > 0 for _, stats in source.values())
    image_count = sum(stats['images'] for _, stats in source.values())
    blank_pages = sum(len(stats['empty_pages']) for _, stats in source.values())
    pages = sum(stats['pages'] for _, stats in source.values())
    findings = [(name, stats['image_pages'], stats['empty_pages']) for name, (_, stats) in source.items()
                if stats['image_pages'] or stats['empty_pages']]
    return {'topics':len(TOPICS), 'files':len(source), 'pages':pages, 'mismatches':mismatches,
            'image_files':image_files, 'image_count':image_count, 'empty_pages':blank_pages, 'findings':findings}


def main():
    folder = os.environ.get('LEARNING_DRIVE_FOLDER_ID', '')
    if not re.fullmatch(r'[A-Za-z0-9_-]+', folder): raise ValueError('invalid folder')
    import requests
    import google.auth
    from google.auth.transport.requests import AuthorizedSession
    creds, _ = google.auth.default(scopes=['https://www.googleapis.com/auth/drive.readonly'])
    drive = AuthorizedSession(creds)
    base = origin(os.environ.get('CONTENT_WORKER_ORIGIN', ''))
    catalog = read_remote(requests.Session(), base)
    if catalog is None: raise ValueError('catalog missing')
    before = list_folder(drive, folder)
    result = audit(catalog, before, lambda file: read_pdf(drive, file))
    if before != list_folder(drive, folder): raise ValueError('source changed')
    print(f"::notice title=Lernzettel-Abgleich::{result['topics']} Themen, {result['files']} PDFs, {result['pages']} Seiten. Textabweichungen: {len(result['mismatches'])}; bildhaltige PDFs: {result['image_files']}; erkannte Bilder: {result['image_count']}; Seiten ohne Text: {result['empty_pages']}.")
    for title in result['mismatches']:
        print(f'::warning title=Abweichung::{title}')
    for name, image_pages, empty_pages in result['findings']:
        # Names come from the explicit, fixed topic mappings, not arbitrary Drive metadata.
        print(f"::warning title=PDF-Prüfhinweis::{name}: Bild auf Seite(n) {image_pages or '-'}; ohne extrahierbaren Text auf Seite(n) {empty_pages or '-'}.")
    print('::warning title=Grenzen::Abgleich der extrahierten Texte, keine visuelle oder fachliche Vollständigkeitsprüfung. Tabellen, Grafiken, Bildinhalte und Vektorgrafiken können fehlen oder falsch angeordnet sein.')
    return 1 if result['mismatches'] or result['empty_pages'] or result['image_count'] else 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception:
        print('::error title=Lernzettel-Abgleich::Abgleich unvollständig; keine Quelle oder Zugangsdaten ausgeben und kein Ergebnis als vollständig ansehen.')
        sys.exit(1)
