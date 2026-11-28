"""Read validated, complete practice-content snapshots from optional Drive folders."""
import hashlib
import io
import json
import os
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
from import_revised_cases import parse_revised_case
from import_notes import list_children, extract_pdf, TOPICS

MAX_FILE = 8 * 1024 * 1024
MAX_WORKBOOK_UNCOMPRESSED = 32 * 1024 * 1024


def inventory(drive, folder, kind):
    if not re.fullmatch(r'[A-Za-z0-9_-]+', folder):
        raise ValueError('Ungültige Übungsordner-ID')
    files = list_children(drive, folder)
    if kind == 'cases-v2':
        name = 'Fallbeispiele mit Zeilennummern – Arbeitsstand'
        folders = [f for f in files if f.get('name') == name]
        if len(folders) != 1 or folders[0].get('mimeType') != 'application/vnd.google-apps.folder':
            raise ValueError('Fall-Unterordner fehlt oder ist mehrdeutig')
        children = list_children(drive, folders[0]['id'])
        # Explicitly labelled old versions are not candidates for publication.
        selected = [f for f in children if not f.get('name', '').startswith('ALTSTAND_')]
        if len(selected) != 30 or any(f.get('mimeType') != 'application/pdf' or
                not re.fullmatch(r'Tag_[123]_Fall_\d{2}_[A-Za-z0-9_]+\.pdf', f.get('name', '')) for f in selected):
            raise ValueError('Neue Falldateien fehlen, sind doppelt oder unbekannt')
        pairs = [(int(f['name'][4]), int(f['name'][11:13])) for f in selected]
        if set(pairs) != {(d, n) for d in range(1, 4) for n in range(1, 11)}:
            raise ValueError('Neue Fälle haben doppelte oder fehlende Nummern')
        return {'folder': folders[0], 'files': sorted(selected, key=lambda f: (f['name'], f['id']))}
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


def revised_case_fingerprint(snapshot):
    # Inventory includes file identity, metadata, and Drive checksums. The same
    # inventory is checked again after downloads by import_practice.
    return hashlib.sha256(json.dumps(snapshot, sort_keys=True, ensure_ascii=False,
                                     separators=(',', ':')).encode('utf-8')).hexdigest()


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


def retire_replaced_cases(base, values):
    old = {item['id'] for item in base['cases']}
    new = {item['id'] for item in values}
    retired = set(base.get('retiredCaseIds', []))
    if (len(new) != 30 or len(values) != 30 or
            not all(re.fullmatch(r'case-v2-[0-9a-f]{64}', value) for value in new) or
            any(sum(c['examDay'] == day for c in values) != 10 for day in (1, 2, 3)) or
            not retired.isdisjoint(new)):
        raise ValueError('Neue Fälle unvollständig oder stillgelegte ID erneut verwendet')
    legacy_ids = {f'case-day-{day}-{number:02d}' for day in range(1, 4) for number in range(1, 11)}
    if old == legacy_ids:
        if retired:
            raise ValueError('Alter Fallbestand stimmt nicht mit dem geprüften Wechsel überein')
    elif not old or not all(re.fullmatch(r'case-v2-[0-9a-f]{64}', value) for value in old):
        raise ValueError('Unbekannte Fall-ID-Generation')
    elif len(old - new) > 3:
        raise ValueError('Zu viele Fälle fehlen; Überprüfung erforderlich')
    return sorted(retired | (old - new))


def import_practice(base, drive, cards_folder='', cases_folder='', card_format='legacy', case_format='legacy'):
    if card_format not in ('legacy', 'revised') or case_format not in ('legacy', 'revised'):
        raise ValueError('Unbekanntes Übungsformat')
    result = dict(base)
    snapshots = []
    for kind, folder in (('cards', cards_folder), ('cases', cases_folder)):
        if not folder:
            continue
        inventory_kind = ('cards-v2' if kind == 'cards' and card_format == 'revised' else
                          'cases-v2' if kind == 'cases' and case_format == 'revised' else kind)
        before = inventory(drive, folder, inventory_kind)
        if kind == 'cases' and case_format == 'revised' and any(not c['id'].startswith('case-v2-') for c in base['cases']):
            expected = os.environ.get('PRACTICE_CASE_INVENTORY_SHA256', '')
            if expected and (not re.fullmatch(r'[0-9a-f]{64}', expected) or revised_case_fingerprint(before) != expected):
                raise ValueError('Fallquellen weichen vom geprüften Bestand ab')
        if kind == 'cards':
            if card_format == 'revised':
                values = read_revised_cards(workbook(download(drive, before[0])))
                result['retiredCardIds'] = retire_replaced_cards(base, values)
            else:
                values = read_cards({f['name']: workbook(download(drive, f)) for f in before})
        else:
            old = {c['id']: c for c in base['cases']}
            values = []
            case_files = before['files'] if case_format == 'revised' else before
            for f in case_files:
                try:
                    modified = date.fromisoformat(f['modifiedTime'][:10]).isoformat()
                except (KeyError, ValueError, TypeError):
                    raise ValueError('Fall-Änderungsdatum ungültig')
                parser = parse_revised_case if case_format == 'revised' else parse_case
                try:
                    item = parser(f['name'], extract_pdf(download(drive, f)), modified)
                except ValueError as exc:
                    if case_format == 'revised':
                        label = re.match(r'Tag_([123])_Fall_(\d{2})_', f['name'])
                        if label:
                            safe_errors = {'Ungültige Abschnitte im neuen Fall', 'Prüfungstag, Fallnummer oder Arbeitsstand fehlt',
                                           'Titel oder Zeilennummern ungültig', 'Ungültige oder fehlende Aufgabennummern',
                                           'Leere Aufgabe oder Lösung', 'Aufgaben und Lösungen sind unvollständig',
                                           'Aufgabe und Lösung stimmen nicht überein', 'Lösung oder Fundstelle fehlt'}
                            reason = str(exc) if str(exc) in safe_errors else 'Prüfung fehlgeschlagen'
                            raise ValueError(f'Fallprüfung Tag {label.group(1)} Fall {label.group(2)}: {reason}') from exc
                    raise
                previous = old.get(item['id'])
                if previous:
                    if previous['sources'][0] != item['sources'][0]:
                        raise ValueError('Fall-ID verweist auf eine andere Datei')
                    item['published'] = previous['published']
                values.append(item)
            if len({v['id'] for v in values}) != len(values):
                raise ValueError('Doppelte Fall-ID')
            if case_format == 'revised':
                result['retiredCaseIds'] = retire_replaced_cases(base, values)
                # case_files were sorted by day and number; preserve this order.
            else:
                values.sort(key=lambda c: c['id'])
        replaced = (kind == 'cards' and card_format == 'revised') or (kind == 'cases' and case_format == 'revised')
        if not replaced and not {v['id'] for v in base[kind]}.issubset({v['id'] for v in values}):
            raise ValueError('Bestehende Karten oder Fälle fehlen; kein Update veröffentlicht')
        result[kind] = values
        snapshots.append((inventory_kind, folder, before))
    # Recheck all source folders only after every download/parse has finished,
    # so changes to the first folder while reading the second are detected.
    for kind, folder, before in snapshots:
        if before != inventory(drive, folder, kind):
            raise ValueError('Übungsordner während des Imports geändert')
    return result
