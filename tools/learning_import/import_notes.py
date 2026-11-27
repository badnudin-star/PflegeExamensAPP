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
# Explicit mappings for the two current versions in Schriftliches Examen.
# Some PDFs intentionally cover several of the 42 fixed preparation topics.
NEW_SLUGS = [
 'Altersgerechte_Kommunikation', 'Atopische_Dermatitis', 'Entwicklung_Erikson',
 'Herzinsuffizienz', 'Konflikteskalation_Glasl', 'Laienpflege_Professionelle_Pflege',
 'Soziale_Rolle_Familienalltag', 'NANDA_Pflegediagnosen', 'Pflegebeduerftigkeit_SGB_V_XI',
 'Soziale_Rolle_Familienalltag', 'S3_Leitlinie_Teiluebersicht', 'Pflegebeduerftigkeit_SGB_V_XI',
 'Pflegende_Angehoerige', 'Qualitaetssicherung', 'Beratung_Information_Schulung',
 'Corbin_Strauss', 'Ernaehrungsmanagement', 'Expertenstandards',
 'Gesundheitsfoerderung_Praevention_Vorsorge', 'Lymphoedem', 'Mammakarzinom',
 'Pflege_bei_Chemotherapie', 'Pflegeforschung', 'Gesundheitsfoerderung_Praevention_Vorsorge',
 'Rheumatoide_Arthritis', 'Sturzprophylaxe', 'Gesundheitsfoerderung_Praevention_Vorsorge',
 'Pflegeethik', 'Fallbesprechung_Kollegiale_Beratung', 'Hypoxischer_Hirnschaden',
 'Handeln_im_Notfall', 'Lungenembolie', 'Nosokomiale_Infektion', 'Palliativpflege',
 'Patientenverfuegung', 'Pflege_bei_Fieber', 'Pflegeethik', 'Reanimation',
 'Schenkelhalsfraktur', 'Thrombose', 'Sondenkost', 'Wundmanagement',
]
MAX_PDF = 5 * 1024 * 1024
MAX_CATALOG = 8 * 1024 * 1024


# New dedicated topic files replace older combined PDFs where available. The detailed
# versions of these eight files currently have no _Neu suffix in Drive.
DEDICATED_SLUGS = {
    'Leben_im_Familienalltag': 'Leben_im_Familienalltag',
    'SGB_V_und_SGB_XI': 'SGB_V_und_SGB_XI',
    'Soziale_Rolle': 'Soziale_Rolle',
    'Pflegebeduerftigkeit': 'Pflegebeduerftigkeit',
    'Gesundheitsfoerderung': 'Gesundheitsfoerderung',
    'Praevention': 'Praevention',
    'Vorsorge': 'Vorsorge',
    'Ethische_Prinzipien_und_Dilemma': 'Ethische_Prinzipien_und_Dilemma',
}


# Explicit current Drive filenames observed for revised source pairs. The old
# names remain supported for already-published offline APKs and backups.
REVISED_PAIRS = {
    'Atopische_Dermatitis_Neurodermitis': ('Atopische_Dermatitis_Neu.pdf', 'Einfach_Atopische_Dermatitis_einfach.pdf'),
    'S3_Leitlinie': ('S3_Leitlinie_Neu.pdf', 'Einfach_S3_Leitlinie_einfach.pdf'),
    'Sturz_und_Sturzprophylaxe': ('Sturzprophylaxe_Neu.pdf', 'Einfach_Sturzprophylaxe_einfach.pdf'),
    'Nosokomiale_Infektion': ('Nosokomiale_Infektion_Neu.pdf', 'Einfach_Nosokomiale_Infektion_einfach.pdf'),
    'Palliativpflege': ('Palliativpflege_Neu.pdf', 'Einfach_Palliativpflege_einfach.pdf'),
    'Patientenverfuegung': ('Patientenverfuegung_Neu.pdf', 'Einfach_Patientenverfuegung_einfach.pdf'),
    'Pflege_bei_Fieber': ('Pflege_bei_Fieber_Neu.pdf', 'Einfach_Pflege_bei_Fieber_einfach.pdf'),
    'Schenkelhalsfraktur': ('Schenkelhalsfraktur_Neu.pdf', 'Einfach_Schenkelhalsfraktur_einfach.pdf'),
    'Wundmanagement': ('Wundmanagement_Neu.pdf', 'Einfach_Wundmanagement_einfach.pdf'),
}


def legacy_topic_candidates(index):
    slug, _ = TOPICS[index]
    old = NEW_SLUGS[index]
    fallback = (f'{old}_Neu.pdf', f'Einfach_{old}.pdf')
    if slug in REVISED_PAIRS:
        return [REVISED_PAIRS[slug], fallback]
    if slug in DEDICATED_SLUGS:
        dedicated = DEDICATED_SLUGS[slug]
        return [(f'{dedicated}.pdf', f'Einfach_{dedicated}.pdf'), fallback]
    return [fallback]


# The finalized Drive layout has one explicitly assigned pair per topic.
# Keep this list explicit: filenames must not decide clinical topics by inference.
CURRENT_SLUGS = [
    'Altersgerechte_Kommunikation', 'Atopische_Dermatitis', 'Entwicklung_Erikson',
    'Herzinsuffizienz', 'Konflikteskalation_Glasl', 'Laienpflege_Professionelle_Pflege',
    'Leben_im_Familienalltag', 'NANDA_Pflegediagnosen', 'SGB_V_und_SGB_XI',
    'Soziale_Rolle', 'S3_Leitlinie', 'Pflegebeduerftigkeit', 'Pflegende_Angehoerige',
    'Qualitaetssicherung', 'Beratung_Information_Schulung', 'Corbin_Strauss',
    'Ernaehrungsmanagement', 'Expertenstandards', 'Gesundheitsfoerderung',
    'Lymphoedem', 'Mammakarzinom', 'Pflege_bei_Chemotherapie', 'Pflegeforschung',
    'Praevention', 'Rheumatoide_Arthritis', 'Sturzprophylaxe', 'Vorsorge',
    'Ethische_Prinzipien_und_Dilemma', 'Fallbesprechung', 'Hypoxischer_Hirnschaden',
    'Handeln_im_Notfall', 'Lungenembolie', 'Nosokomiale_Infektion', 'Palliativpflege',
    'Patientenverfuegung', 'Pflege_bei_Fieber', 'Pflegeethik', 'Reanimation',
    'Schenkelhalsfraktur', 'Thrombose', 'Sondenkost', 'Wundmanagement',
]


def topic_candidates(index):
    slug = CURRENT_SLUGS[index]
    current = (f'{slug}_Neu.pdf', f'Einfach_{slug}_einfach.pdf')
    return [current] + [p for p in legacy_topic_candidates(index) if p != current]


def expected_names():
    # Known names include older combined files as well as the new individual ones.
    known = {}
    for i, (slug, _) in enumerate(TOPICS):
        for detailed, simple in topic_candidates(i):
            known[detailed] = (slug, 'detailed')
            known[simple] = (slug, 'short')
    return known


def topic_files(indexed, index):
    """Prefer a complete individual pair; never mix a partial pair with a fallback."""
    candidates = topic_candidates(index)
    current, legacy = candidates[0], legacy_topic_candidates(index)
    if all(name in indexed for name in current):
        # Multiple complete revised pairs or two simple variants for one detailed PDF are ambiguous.
        if (TOPICS[index][0] in REVISED_PAIRS and
                any(pair != current and all(name in indexed for name in pair) for pair in legacy)):
            raise ValueError('Mehrdeutiges Lernzettelpaar')
        if any(pair[0] == current[0] and pair[1] != current[1] and pair[1] in indexed for pair in legacy):
            raise ValueError('Mehrdeutiges Lernzettelpaar')
        return current
    # A shared detailed filename may belong to the old pair; a new-only file,
    # however, is a partially uploaded new pair and must block the import.
    legacy_names = {name for pair in legacy for name in pair}
    if any(name in indexed and name not in legacy_names for name in current):
        raise ValueError('Unvollständiges Lernzettelpaar')
    if TOPICS[index][0] in REVISED_PAIRS:
        complete = [pair for pair in legacy if all(name in indexed for name in pair)]
        if len(complete) > 1:
            raise ValueError('Mehrdeutiges Lernzettelpaar')
        if complete:
            selected = set(complete[0])
            if any(name in indexed and name not in selected for pair in legacy for name in pair):
                raise ValueError('Unvollständiges alternatives Lernzettelpaar')
            return complete[0]
        if any(name in indexed for pair in legacy for name in pair):
            raise ValueError('Unvollständiges Lernzettelpaar')
    for detailed, simple in legacy:
        present = (detailed in indexed, simple in indexed)
        if all(present): return detailed, simple
        if any(present): raise ValueError(f'Unvollständiges Lernzettelpaar: {detailed}, {simple}')
    raise ValueError(f'Fehlende Quelle für Thema: {TOPICS[index][1]}')


def index_files(files):
    expected, indexed = expected_names(), {}
    for f in files:
        name = f['name']
        if name not in expected:
            if name.lower().endswith('_neu.pdf') or name.lower().startswith('einfach_'):
                raise ValueError(f'Unbekannter Lernzettel: {name}')
            continue
        if name in indexed:
            raise ValueError(f'Mehrdeutige Datei: {name}')
        if f.get('mimeType') != 'application/pdf' or not 0 < int(f.get('size', 0)) <= MAX_PDF:
            raise ValueError(f'Falscher Typ oder unzulässige Größe: {name}')
        indexed[name] = f
    for i in range(len(TOPICS)):
        topic_files(indexed, i)
    return indexed


def assemble(base, indexed, extract):
    if base.get('schemaVersion') != 1 or not isinstance(base.get('revision'), int) or base['revision'] < 0:
        raise ValueError('Ungültiger Basiskatalog')
    if any(not isinstance(base.get(k), list) for k in ('notes', 'cards', 'cases', 'quizzes')):
        raise ValueError('Basiskatalog ist unvollständig')
    pairs = [topic_files(indexed, i) for i in range(len(TOPICS))]
    texts = {}
    for name in dict.fromkeys(name for pair in pairs for name in pair):
        text = extract(indexed[name])
        if not isinstance(text, str) or len(text.strip()) < 80:
            raise ValueError(f'Keine lesbare Textschicht: {name}')
        texts[name] = text.strip()
    notes = []
    for (_, title), (main, simple) in zip(TOPICS, pairs, strict=True):
        # Keep the original schema valid for deployed Workers and older clients.
        notes.append({'topic': title, 'short': texts[simple], 'balanced': texts[main],
                      'detailed': texts[main], 'formatVersion': 2,
                      'sources': ['Lernzettel: ' + main, 'Lernzettel: ' + simple]})
    result = dict(base)
    result.update(revision=base['revision'] + 1, notes=notes)
    encoded = (json.dumps(result, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8')
    if len(encoded) > MAX_CATALOG:
        raise ValueError('Katalog überschreitet das 8-MB-Limit')
    return encoded


def list_children(session, folder):
    files, token = [], None
    while True:
        params = {'q': f"'{folder}' in parents and trashed = false", 'pageSize': 100,
                  'fields': 'nextPageToken,files(id,name,mimeType,size,modifiedTime,md5Checksum)',
                  'supportsAllDrives': 'true', 'includeItemsFromAllDrives': 'true'}
        if token: params['pageToken'] = token
        response = session.get('https://www.googleapis.com/drive/v3/files', params=params, timeout=60)
        response.raise_for_status()
        body = response.json()
        files.extend(body['files'])
        token = body.get('nextPageToken')
        if not token: return files


def list_folder(session, folder):
    # Configured ID remains the shared parent; do not require changing a GitHub variable.
    children = list_children(session, folder)
    folders = [f for f in children if f['name'] == 'Schriftliches Examen' and f.get('mimeType') == 'application/vnd.google-apps.folder']
    if len(folders) != 1: raise ValueError('Unterordner Schriftliches Examen fehlt oder ist mehrdeutig')
    return index_files(list_children(session, folders[0]['id']))


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


def download_text(session, f):
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
    data = assemble(json.loads(args.base.read_text(encoding='utf-8')), before, lambda f: download_text(session, f))
    if before != list_folder(session, args.folder):
        raise ValueError('Drive-Ordner während des Imports geändert; kein Katalog geschrieben')
    save_atomic(args.output, data)
    print(f'{len(TOPICS)} Themen mit je zwei Lernzetteln importiert. Keine Veröffentlichung erfolgt.')


if __name__ == '__main__':
    main()
