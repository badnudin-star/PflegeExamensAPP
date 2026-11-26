"""Read validated, complete practice-content snapshots from optional Drive folders."""
import hashlib
import io
import re
import sys
from datetime import date
from pathlib import Path
from urllib.parse import quote
from zipfile import ZipFile, BadZipFile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'card_import'))
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'case_import'))
from import_cards import FILES, read_cards
from import_cases import parse_case
from import_notes import list_children, extract_pdf

MAX_FILE = 8 * 1024 * 1024
MAX_WORKBOOK_UNCOMPRESSED = 32 * 1024 * 1024


def inventory(drive, folder, kind):
    if not re.fullmatch(r'[A-Za-z0-9_-]+', folder):
        raise ValueError('Ungültige Übungsordner-ID')
    files = list_children(drive, folder)
    if kind == 'cards':
        selected = [f for f in files if f.get('name') in FILES]
        if len(selected) != len(FILES) or {f['name'] for f in selected} != set(FILES):
            raise ValueError('Die beiden aktuellen Karteikarten-Arbeitsmappen fehlen oder sind doppelt')
        expected_mime = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
    else:
        selected = [f for f in files if f.get('mimeType') == 'application/pdf' or f.get('name', '').startswith('Tag_')]
        if not 1 <= len(selected) <= 300:
            raise ValueError('Fallordner leer oder zu viele Dateien')
        expected_mime = 'application/pdf'
        if any(not re.fullmatch(r'Tag_[123]_Fall_\d{2}_.+\.pdf', f.get('name', '')) for f in selected):
            raise ValueError('Unbekannte Datei im Fallordner')
    if any(f.get('mimeType') != expected_mime for f in selected):
        raise ValueError('Falscher Dateityp im Übungsordner')
    return sorted(selected, key=lambda f: (f['name'], f['id']))


def download(drive, f):
    try:
        size = int(f['size'])
    except (KeyError, ValueError, TypeError):
        raise ValueError('Dateigröße fehlt')
    if not 0 < size <= MAX_FILE or not re.fullmatch(r'[0-9a-f]{32}', f.get('md5Checksum', '')):
        raise ValueError('Dateigröße oder Prüfsumme ungültig')
    response = drive.get('https://www.googleapis.com/drive/v3/files/' + quote(f['id'], safe=''),
                         params={'alt': 'media', 'supportsAllDrives': 'true'}, timeout=60, stream=True)
    response.raise_for_status()
    data = bytearray()
    try:
        for chunk in response.iter_content(65536):
            data.extend(chunk)
            if len(data) > size or len(data) > MAX_FILE:
                raise ValueError('Datei überschreitet erwartete Größe')
    finally:
        response.close()
    if len(data) != size or hashlib.md5(data).hexdigest() != f['md5Checksum']:
        raise ValueError('Drive-Datei während des Imports geändert')
    return bytes(data)


def workbook(data):
    try:
        with ZipFile(io.BytesIO(data)) as archive:
            entries = archive.infolist()
            if len(entries) > 1000 or sum(i.file_size for i in entries) > MAX_WORKBOOK_UNCOMPRESSED:
                raise ValueError('Arbeitsmappe ist entpackt zu groß')
    except BadZipFile as exc:
        raise ValueError('Ungültige Arbeitsmappe') from exc
    return io.BytesIO(data)


def import_practice(base, drive, cards_folder='', cases_folder=''):
    result = dict(base)
    for kind, folder in (('cards', cards_folder), ('cases', cases_folder)):
        if not folder:
            continue
        before = inventory(drive, folder, kind)
        if kind == 'cards':
            values = read_cards({f['name']: workbook(download(drive, f)) for f in before})
        else:
            old = {c['id']: c for c in base['cases']}
            values = []
            for f in before:
                try:
                    modified = date.fromisoformat(f['modifiedTime'][:10]).isoformat()
                except (KeyError, ValueError, TypeError):
                    raise ValueError('Fall-Änderungsdatum ungültig')
                item = parse_case(f['name'], extract_pdf(download(drive, f)), modified)
                previous = old.get(item['id'])
                if previous:
                    if previous['sources'][0] != item['sources'][0]:
                        raise ValueError('Fall-ID verweist auf eine andere Datei')
                    item['published'] = previous['published']
                values.append(item)
            if len({v['id'] for v in values}) != len(values):
                raise ValueError('Doppelte Fall-ID')
            values.sort(key=lambda c: c['id'])
        if not {v['id'] for v in base[kind]}.issubset({v['id'] for v in values}):
            raise ValueError('Bestehende Karten oder Fälle fehlen; kein Update veröffentlicht')
        if before != inventory(drive, folder, kind):
            raise ValueError('Übungsordner während des Imports geändert')
        result[kind] = values
    return result
