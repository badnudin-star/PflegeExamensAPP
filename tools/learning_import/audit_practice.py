#!/usr/bin/env python3
"""Read-only Drive practice content check; never writes to Drive or the Worker."""
import os
import json
from pathlib import Path
import re
import sys
from sync_drive import origin, read_remote, validate_base, sync
from sync_practice import import_practice


def summarize(base, candidate):
    result = []
    for kind, label in (('cards', 'Karten'), ('cases', 'Fälle')):
        old = {item['id']: item for item in base[kind]}
        new = {item['id']: item for item in candidate[kind]}
        if not old.keys() <= new.keys():
            raise ValueError('Bestehende IDs fehlen')
        added = len(new.keys() - old.keys())
        changed = sum(new[key] != old[key] for key in old)
        result.append(f'{label}: {len(new)} insgesamt, {added} neu, {changed} geändert')
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
    candidate = validate_base(import_practice(remote, drive, cards, cases))
    print('::notice title=Übungsinhalte-Abgleich::' + summarize(remote, candidate) + '. Keine Veröffentlichung.', flush=True)
    # Use the real complete pipeline with a transport that cannot publish, even
    # if the check-only implementation regresses. No publication secret is used.
    class ReadOnlyHttp:
        def __init__(self): self.session = requests.Session()
        def get(self, *args, **kwargs): return self.session.get(*args, **kwargs)
        def put(self, *args, **kwargs): raise RuntimeError('Publication forbidden during audit')
    folder = os.environ.get('LEARNING_DRIVE_FOLDER_ID', '')
    bundled = json.loads(Path('app/src/main/assets/learning_content.json').read_text(encoding='utf-8'))
    state = sync(ReadOnlyHttp(), origin(os.environ.get('CONTENT_WORKER_ORIGIN', '')),
                 bundled, folder, drive, 'x' * 32, cards, cases, check_only=True)
    print('::notice title=Vollständiger Import::' + ('Keine Änderungen erkannt.' if state == 'checked-unchanged' else 'Änderungen erkannt; nicht veröffentlicht.'), flush=True)
    print('::notice title=Prüfgrenzen::Nur Struktur und Vergleich getestet, keine fachliche Prüfung oder Geräteprüfung.', flush=True)


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        # Do not expose source text, credential paths, tokens, or arbitrary API responses.
        safe = ('Beide Übungsordner-Variablen', 'Öffentlicher Katalog fehlt', 'Bestehende IDs fehlen',
                'Die beiden aktuellen Karteikarten-Arbeitsmappen fehlen', 'Fallordner leer',
                'Unbekannte Datei im Fallordner', 'Falscher Dateityp', 'Bestehende Karten oder Fälle fehlen',
                'Fall-ID verweist auf eine andere Datei', 'Doppelte Fall-ID', 'Übungsordner während des Imports geändert',
                'Dateigröße oder Prüfsumme ungültig', 'Drive-Datei während des Imports geändert')
        message = str(exc) if type(exc) is ValueError and str(exc).startswith(safe) else type(exc).__name__
        status = getattr(getattr(exc, 'response', None), 'status_code', None)
        if isinstance(status, int): message += f' (HTTP {status})'
        print('::error title=Übungsinhalte-Abgleich::' + message.replace('%', '%25').replace('\n', '%0A').replace('\r', '%0D'), flush=True)
        sys.exit(1)
