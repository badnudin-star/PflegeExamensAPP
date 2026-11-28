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
from import_revised_cards import FILENAME as REVISED_CARDS, read_revised_cards
from import_cases import parse_case
from import_notes import list_children, extract_pdf, TOPICS

MAX_FILE = 8 * 1024 * 1024
MAX_WORKBOOK_UNCOMPRESSED = 32 * 1024 * 1024


def inventory(drive, folder, kind):
    if not re.fullmatch(r'[A-Za-z0-9_-]+', folder):
        raise ValueError('Ungültige Übungsordner-ID')
    files = list_children(drive, folder)
    if kind in ('cards', 'cards-v2'):
        names = set(FILES) if kind == 'cards' else {REVISED_CARDS}
        selected = [f for f in files if f.get('name') in names]
        if len(selected) != len(names) or {f['name'] for f in selected} != names:
            raise ValueError('Die erwarteten Karteikarten-Arbeitsmappen fehlen oder sind doppelt')
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


def retire_replaced_cards(base, values):
    """Retire identities explicitly; never reuse them or silently migrate progress."""
    old = {card['id'] for card in base['cards']}
    new = {card['id'] for card in values}
    retired = set(base.get('retiredCardIds', []))
    topics = {title for _, title in TOPICS}
    if (len(new) != len(values) or not all(re.fullmatch(r'card-v2-[0-9a-f]{64}', x) for x in new) or
            {c['topic'] for c in values} != topics or not retired.isdisjoint(new)):
        raise ValueError('Kartenbestand unvollständig oder stillgelegte ID erneut verwendet')
    legacy = bool(old) and all(re.fullmatch(r'card-\d{2}-\d{3}', x) for x in old)
    if legacy:
        # One explicit migration of the previously audited catalog, not a generic
        # escape hatch for an incomplete source. Future imports use v2 IDs only.
        if len(old) != 523 or len(values) != 481 or {c['topic'] for c in base['cards']} != topics or retired:
            raise ValueError('Alter Kartenbestand stimmt nicht mit dem geprüften Wechsel überein')
    elif not old or not all(re.fullmatch(r'card-v2-[0-9a-f]{64}', x) for x in old):
        raise ValueError('Unbekannte Karten-ID-Generation')
    else:
        removed = old - new
        if len(removed) > 25 or len(removed) * 10 > len(old):
            raise ValueError('Zu viele Karten fehlen; Überprüfung erforderlich')
    return sorted(retired | (old - new))


def import_practice(base, drive, cards_folder='', cases_folder='', card_format='legacy'):
    if card_format not in ('legacy', 'revised'):
        raise ValueError('Unbekanntes Kartenformat')
    result = dict(base)
    snapshots = []
    for kind, folder in (('cards', cards_folder), ('cases', cases_folder)):
        if not folder:
            continue
        inventory_kind = 'cards-v2' if kind == 'cards' and card_format == 'revised' else kind
        before = inventory(drive, folder, inventory_kind)
        if kind == 'cards':
            if card_format == 'revised':
                values = read_revised_cards(workbook(download(drive, before[0])))
                result['retiredCardIds'] = retire_replaced_cards(base, values)
            else:
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
        if not (kind == 'cards' and card_format == 'revised') and not {v['id'] for v in base[kind]}.issubset({v['id'] for v in values}):
            raise ValueError('Bestehende Karten oder Fälle fehlen; kein Update veröffentlicht')
        result[kind] = values
        snapshots.append((inventory_kind, folder, before))
    # Recheck all source folders only after every download/parse has finished,
    # so changes to the first folder while reading the second are detected.
    for kind, folder, before in snapshots:
        if before != inventory(drive, folder, kind):
            raise ValueError('Übungsordner während des Imports geändert')
    return result
