#!/usr/bin/env python3
"""Create a private snapshot from the user's 42 x 3 Drive PDFs. Nothing is published by default."""
import argparse
import io
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import quote

# Explicit mapping: filenames are not used to infer a clinical topic.
TOPICS = [
 ('Altersgerechte_Kommunikation', '(Altersgerechte) Kommunikation'),
 ('Atopische_Dermatitis_Neurodermitis', 'Atopische Dermatitis (Neurodermitis)'),
 ('Entwicklung_nach_Erikson', 'Entwicklung (nach Erikson)'),
 ('Herzinsuffizienz', 'Herzinsuffizienz'),
 ('Konflikteskalation_nach_Glasl', 'Konflikteskalation (Glasl)'),
 ('Laienpflege_und_professionelle_Pflege', 'Laienpflege / Professionelle Pflege'),
 ('Leben_im_Familienalltag', 'Leben im (Familien-) Alltag'),
 ('NANDA_Pflegediagnosen', 'NANDA Pflegediagnosen (allgemein)'),
 ('SGB_V_und_SGB_XI', 'SGB V und SGB XI'),
 ('Soziale_Rolle', 'Soziale Rolle'), ('S3_Leitlinie', 'S3-Leitlinie'),
 ('Pflegebeduerftigkeit', 'Pflegebedürftigkeit'),
 ('Pflegende_Angehoerige_und_Belastung', 'Pflegende Angehörige / Belastung'),
 ('Qualitaetssicherung', 'Qualitätssicherung'), ('Beratung_auch_kollegial', 'Beratung (auch kollegial)'),
 ('Corbin_Strauss', 'Corbin-Strauss'), ('Ernaehrungsmanagement_und_Expertenstandard', 'Ernährung Expertenstandard'),
 ('Expertenstandards_allgemein', 'Expertenstandards allgemein'), ('Gesundheitsfoerderung', 'Gesundheitsförderung'),
 ('Lymphoedem', 'Lymphödem'), ('Mammakarzinom', 'Mamma CA'),
 ('Pflege_bei_Chemotherapie', '(Pflege bei) Chemotherapie'), ('Pflegeforschung', 'Pflegeforschung'),
 ('Praevention', 'Prävention'), ('Rheumatoide_Arthritis', 'Rheumatoide Arthritis'),
 ('Sturz_und_Sturzprophylaxe', 'Sturz (-prophylaxe)'), ('Vorsorge', 'Vorsorge'),
 ('Ethische_Prinzipien_und_Dilemma', 'Ethische Prinzipien / Dilemma'), ('Fallbesprechung', 'Fallbesprechung'),
 ('Hypoxischer_Hirnschaden', 'Hypoxischer Hirnschaden'), ('Handeln_im_Notfall', 'Handeln im Notfall'),
 ('Lungenembolie', 'Lungenembolie'), ('Nosokomiale_Infektion', 'Nosokomiale Infektion'),
 ('Palliativpflege', 'Palliativpflege'), ('Patientenverfuegung', 'Patientenverfügung'),
 ('Pflege_bei_Fieber', 'Pflege bei Fieber'), ('Pflegeethik', 'Pflegeethik'),
 ('Reanimation_Erwachsene_und_Kinder', 'Reanimation (auch Kinder)'),
 ('Schenkelhalsfraktur', 'Schenkelhalsfraktur'), ('Tiefe_Venenthrombose', 'Thrombose'),
 ('Umgang_mit_Sondenkost', 'Umgang mit Sondenkost'), ('Wundmanagement', 'Wundmanagement'),
]
VARIANTS = {'Kurz': 'short', 'Ausgeglichen': 'balanced', 'Ausfuehrlich': 'detailed'}
MAX_PDF = 5 * 1024 * 1024
MAX_CATALOG = 8 * 1024 * 1024


def expected_names():
    return {f'{number:02d}_{slug}_{variant}.pdf': (title, field)
            for number, (slug, title) in enumerate(TOPICS, 1)
            for variant, field in VARIANTS.items()}


def index_files(files):
    expected = expected_names()
    indexed = {}
    for f in files:
        name = f['name']
        if name not in expected:
            if re.match(r'^\d\d_.*\.pdf$', name, re.I):
                raise ValueError(f'Unerwartete nummerierte PDF: {name}')
            continue  # e.g. combined volumes are not individual variants
        if name in indexed:
            raise ValueError(f'Mehrdeutige Datei: {name}')
        if f.get('mimeType') != 'application/pdf' or not 0 < int(f.get('size', 0)) <= MAX_PDF:
            raise ValueError(f'Falscher Typ oder unzulässige Größe: {name}')
        indexed[name] = f
    missing = sorted(set(expected) - set(indexed))
    if missing:
        raise ValueError('Fehlende PDFs: ' + ', '.join(missing))
    return indexed


def assemble(base, indexed, extract):
    if base.get('schemaVersion') != 1 or not isinstance(base.get('revision'), int) or base['revision'] < 0:
        raise ValueError('Ungültiger Basiskatalog')
    if any(not isinstance(base.get(k), list) for k in ('notes', 'cards', 'cases', 'quizzes')):
        raise ValueError('Basiskatalog ist unvollständig')
    notes = {title: {'topic': title, 'sources': []} for _, title in TOPICS}
    for name, (title, field) in expected_names().items():
        text = extract(indexed[name])
        if not isinstance(text, str) or len(text.strip()) < 80:
            raise ValueError(f'Keine lesbare Textschicht: {name}')
        # Preserve the PDF's own source references and page order. No AI or summary.
        notes[title][field] = text.strip()
        notes[title]['sources'].append('Lernzettel: ' + name + ' (Quellen und Fundstellen im Text)')
    result = dict(base)
    result.update(revision=base['revision'] + 1, notes=list(notes.values()))
    encoded = (json.dumps(result, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8')
    if len(encoded) > MAX_CATALOG:
        raise ValueError('Katalog überschreitet das 8-MB-Limit')
    return encoded


def list_folder(session, folder):
    files, token = [], None
    while True:
        params = {'q': f"'{folder}' in parents and trashed = false", 'pageSize': 100,
                  'fields': 'nextPageToken,files(id,name,mimeType,size,modifiedTime,md5Checksum)',
                  'supportsAllDrives': 'true', 'includeItemsFromAllDrives': 'true'}
        if token:
            params['pageToken'] = token
        response = session.get('https://www.googleapis.com/drive/v3/files', params=params, timeout=60)
        response.raise_for_status()
        body = response.json()
        files.extend(body['files'])
        token = body.get('nextPageToken')
        if not token:
            return index_files(files)


def extract_pdf(content):
    from pypdf import PdfReader
    pdf = PdfReader(io.BytesIO(content), strict=True)
    if pdf.is_encrypted or not 1 <= len(pdf.pages) <= 100:
        raise ValueError('PDF ist verschlüsselt oder hat eine unzulässige Seitenzahl')
    return '\n\n'.join((page.extract_text() or '') for page in pdf.pages)


def save_atomic(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as handle:
            tmp = handle.name
            os.chmod(tmp, 0o600)
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp, path)
    finally:
        if tmp and os.path.exists(tmp):
            os.unlink(tmp)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--folder', required=True, help='ID des freigegebenen Lernzettel-Ordners')
    parser.add_argument('--base', required=True, type=Path, help='Aktueller vollständiger JSON-Katalog; niemals nur die Lernzettel')
    parser.add_argument('--output', required=True, type=Path, help='Privater Ausgabepfad; niemals ungeprüft in ein öffentliches Repository')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_-]+', args.folder):
        parser.error('Ungültige Ordner-ID')
    if args.output.resolve() == args.base.resolve():
        parser.error('Ausgabe und Basiskatalog müssen verschiedene Dateien sein')
    import google.auth
    from google.auth.transport.requests import AuthorizedSession
    credentials, _ = google.auth.default(scopes=['https://www.googleapis.com/auth/drive.readonly'])
    session = AuthorizedSession(credentials)
    before = list_folder(session, args.folder)
    def extract(f):
        response = session.get('https://www.googleapis.com/drive/v3/files/' + quote(f['id'], safe=''),
                               params={'alt': 'media', 'supportsAllDrives': 'true'}, timeout=60, stream=True)
        response.raise_for_status()
        content = bytearray()
        for chunk in response.iter_content(65536):
            content.extend(chunk)
            if len(content) > MAX_PDF:
                raise ValueError('PDF überschreitet das Größenlimit')
        if len(content) != int(f['size']) or not f.get('md5Checksum') or hashlib.md5(content).hexdigest() != f['md5Checksum']:
            raise ValueError(f"Dateiinhalt während des Imports geändert: {f['name']}")
        return extract_pdf(bytes(content))
    data = assemble(json.loads(args.base.read_text(encoding='utf-8')), before, extract)
    if before != list_folder(session, args.folder):
        raise ValueError('Drive-Ordner während des Imports geändert; kein Katalog geschrieben')
    save_atomic(args.output, data)
    print(f'{len(TOPICS)} Themen mit je drei Lernzetteln importiert. Keine Veröffentlichung erfolgt.')


if __name__ == '__main__':
    main()
