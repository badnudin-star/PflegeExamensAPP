#!/usr/bin/env python3
"""Read-only structural audit of the revised card workbook; no publishing token."""
import os
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'card_import'))
from import_revised_cards import FILENAME, read_revised_cards
from import_notes import list_children
from sync_practice import download, workbook


def audit(drive, folder):
    if not re.fullmatch(r'[A-Za-z0-9_-]+', folder):
        raise ValueError('Ungültige Kartenordner-ID')
    def selected():
        matches = [f for f in list_children(drive, folder) if f.get('name') == FILENAME]
        if len(matches) != 1 or matches[0].get('mimeType') != 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet':
            raise ValueError('Überarbeitete Arbeitsmappe fehlt, ist doppelt oder hat den falschen Typ')
        return matches[0]
    before = selected()
    cards = read_revised_cards(workbook(download(drive, before)))
    if selected() != before:
        raise ValueError('Arbeitsmappe während der Prüfung geändert')
    return len(cards), len({card['topic'] for card in cards})


def main():
    import google.auth
    from google.auth.transport.requests import AuthorizedSession
    credentials, _ = google.auth.default(scopes=['https://www.googleapis.com/auth/drive.readonly'])
    count, topics = audit(AuthorizedSession(credentials), os.environ.get('PRACTICE_CARDS_FOLDER_ID', ''))
    print(f'::notice title=Neue Karteikarten::{count} Karten für {topics} Themen strukturell geprüft. Nichts veröffentlicht.', flush=True)
    print('::notice title=Prüfgrenzen::Keine fachliche Prüfung, kein Geräte- oder Veröffentlichungsnachweis.', flush=True)


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        # Show only predefined messages. Do not disclose workbook contents or credentials.
        allowed = ('Ungültige Kartenordner-ID', 'Überarbeitete Arbeitsmappe fehlt',
                   'Arbeitsmappe während der Prüfung geändert', 'Übersicht oder Kartenverzeichnis fehlt',
                   'Übersicht hat keine eindeutige Kopfzeile', 'Übersicht muss genau 42 Themen enthalten',
                   'Ungültiger Übersichtseintrag', 'Kartenverzeichnis hat eine unerwartete Kopfzeile',
                   'Ungültige Kartennummer', 'Unbekannte Themennummer', 'Ungültige Zuordnung bei Karte',
                   'Kartenfeld fehlt', 'Doppelte Karte', 'Kartenzahlen stimmen', 'Formeln im Kartenverzeichnis',
                   'Unerwartete zusätzliche Daten', 'Dateigröße oder Prüfsumme ungültig',
                   'Drive-Datei während des Imports geändert')
        message = str(exc) if type(exc) is ValueError and str(exc).startswith(allowed) else type(exc).__name__
        status = getattr(getattr(exc, 'response', None), 'status_code', None)
        if isinstance(status, int): message += f' (HTTP {status})'
        print('::error title=Neue Karteikarten::' + message.replace('%', '%25').replace('\n', '%0A').replace('\r', '%0D'), flush=True)
        sys.exit(1)
