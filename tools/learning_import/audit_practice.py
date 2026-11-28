#!/usr/bin/env python3
"""Read-only Drive practice content check; never writes to Drive or the Worker."""
import os
import json
from pathlib import Path
import re
import sys
from sync_drive import origin, read_remote, validate_base, sync
from sync_practice import import_practice, inventory, revised_case_fingerprint


def summarize(base, candidate):
    result = []
    for kind, label in (('cards', 'Karten'), ('cases', 'Fälle')):
        old = {item['id']: item for item in base[kind]}
        new = {item['id']: item for item in candidate[kind]}
        retired = set(candidate.get('retiredCardIds' if kind == 'cards' else 'retiredCaseIds', []))
        if not old.keys() <= (new.keys() | retired):
            raise ValueError('Bestehende IDs fehlen')
        added = len(new.keys() - old.keys())
        removed = len(old.keys() - new.keys())
        changed = sum(new[key] != old[key] for key in old.keys() & new.keys())
        suffix = f', {removed} stillgelegt' if removed else ''
        result.append(f'{label}: {len(new)} insgesamt, {added} neu, {changed} geändert{suffix}')
    return '; '.join(result)


def main():
    cards = os.environ.get('PRACTICE_CARDS_FOLDER_ID', '')
    cases = os.environ.get('PRACTICE_CASES_FOLDER_ID', '')
    if any(not re.fullmatch(r'[A-Za-z0-9_-]+', value) for value in (cards, cases)):
        raise ValueError('Beide Übungsordner-Variablen müssen eingerichtet sein')
    import requests
    import google.auth
    from google.auth.transport.requests import AuthorizedSession
    credentials, _ = google.auth.default(scopes=['https://www.googleapis.com/auth/drive.readonly'])
    remote = read_remote(requests.Session(), origin(os.environ.get('CONTENT_WORKER_ORIGIN', '')))
    if remote is None:
        raise ValueError('Öffentlicher Katalog fehlt')
    drive = AuthorizedSession(credentials)
    card_format = os.environ.get('PRACTICE_CARD_FORMAT', 'legacy')
    case_format = os.environ.get('PRACTICE_CASE_FORMAT', 'legacy')
    case_inventory = inventory(drive, cases, 'cases-v2') if case_format == 'revised' else None
    candidate = validate_base(import_practice(remote, drive, cards, cases, card_format=card_format, case_format=case_format))
    print('::notice title=Übungsinhalte-Abgleich::' + summarize(remote, candidate) + '. Keine Veröffentlichung.', flush=True)
    print('::notice title=Fallaufgaben::' + str(sum(len(item['questions']) for item in candidate['cases'])) + ' Aufgaben in ' + str(len(candidate['cases'])) + ' Fällen geprüft.', flush=True)
    # Use the real complete pipeline with a transport that cannot publish, even
    # if the check-only implementation regresses. No publication secret is used.
    class ReadOnlyHttp:
        def __init__(self): self.session = requests.Session()
        def get(self, *args, **kwargs): return self.session.get(*args, **kwargs)
        def put(self, *args, **kwargs): raise RuntimeError('Publication forbidden during audit')
    folder = os.environ.get('LEARNING_DRIVE_FOLDER_ID', '')
    bundled = json.loads(Path('app/src/main/assets/learning_content.json').read_text(encoding='utf-8'))
    state = sync(ReadOnlyHttp(), origin(os.environ.get('CONTENT_WORKER_ORIGIN', '')),
                 bundled, folder, drive, 'x' * 32, cards, cases, check_only=True, card_format=card_format, case_format=case_format)
    if case_inventory is not None:
        if inventory(drive, cases, 'cases-v2') != case_inventory:
            raise ValueError('Fallquellen weichen vom geprüften Bestand ab')
        print('::notice title=Fallquellen-Prüfsumme::' + revised_case_fingerprint(case_inventory), flush=True)
    print('::notice title=Vollständiger Import::' + ('Keine Änderungen erkannt.' if state == 'checked-unchanged' else 'Änderungen erkannt; nicht veröffentlicht.'), flush=True)
    print('::notice title=Prüfgrenzen::Nur Struktur und Vergleich getestet, keine fachliche Prüfung oder Geräteprüfung.', flush=True)


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        # Do not expose source text, credential paths, tokens, or arbitrary API responses.
        safe = ('Beide Übungsordner-Variablen', 'Öffentlicher Katalog fehlt', 'Bestehende IDs fehlen',
                'Die erwarteten Karteikarten-Arbeitsmappen fehlen', 'Kartenbestand unvollständig',
                'Alter Kartenbestand stimmt nicht', 'Unbekannte Karten-ID-Generation',
                'Zu viele Karten fehlen', 'Fallprüfung Tag ', 'Fall-Unterordner fehlt', 'Neue Falldateien fehlen',
                'Neue Fälle haben doppelte', 'Ungültige Abschnitte im neuen Fall',
                'Prüfungstag, Fallnummer', 'Titel oder Zeilennummern ungültig',
                'Ungültige oder fehlende Aufgabennummern', 'Leere Aufgabe oder Lösung',
                'Aufgaben und Lösungen sind unvollständig', 'Aufgabe und Lösung stimmen nicht',
                'Lösung oder Fundstelle fehlt', 'Neue Fälle unvollständig',
                'Alter Fallbestand stimmt nicht', 'Unbekannte Fall-ID-Generation',
                'Zu viele Fälle fehlen', 'Fallordner leer',
                'Unbekannte Datei im Fallordner', 'Falscher Dateityp', 'Bestehende Karten oder Fälle fehlen',
                'Fall-ID verweist auf eine andere Datei', 'Doppelte Fall-ID', 'Übungsordner während des Imports geändert',
                'Dateigröße oder Prüfsumme ungültig', 'Drive-Datei während des Imports geändert')
        message = str(exc) if type(exc) is ValueError and str(exc).startswith(safe) else type(exc).__name__
        status = getattr(getattr(exc, 'response', None), 'status_code', None)
        if isinstance(status, int): message += f' (HTTP {status})'
        print('::error title=Übungsinhalte-Abgleich::' + message.replace('%', '%25').replace('\n', '%0A').replace('\r', '%0D'), flush=True)
        sys.exit(1)
