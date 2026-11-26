#!/usr/bin/env python3
"""Publish complete Drive-note snapshots to a configured content Worker (server only)."""
import argparse
import json
import os
from pathlib import Path
import re
from urllib.parse import urlsplit

from import_notes import TOPICS, MAX_CATALOG, assemble, list_folder, download_text
from catalog_capacity import report_capacity
from sync_practice import import_practice


def origin(value):
    url = urlsplit(value)
    if (url.scheme != 'https' or not url.hostname or url.username or url.password or
            url.path not in ('', '/') or url.query or url.fragment):
        raise ValueError('Worker-URL muss eine HTTPS-Origin ohne Pfad, Passwort oder Parameter sein')
    return value.rstrip('/')


def validate_base(c):
    if (not isinstance(c, dict) or c.get('schemaVersion') != 1 or
            type(c.get('revision')) is not int or not 0 <= c['revision'] < 2**53-1 or
            any(not isinstance(c.get(k), list) for k in ('notes', 'cards', 'cases', 'quizzes'))):
        raise ValueError('Ungültiger oder unvollständiger Basiskatalog')
    titles = {title for _, title in TOPICS}
    def text(s): return isinstance(s, str) and bool(s.strip())
    def sources(s): return isinstance(s, list) and bool(s) and all(text(v) for v in s)
    def unique(items, key):
        return all(isinstance(x, dict) and text(x.get(key)) for x in items) and len({x[key] for x in items}) == len(items)
    if (not unique(c['notes'], 'topic') or any(x['topic'] not in titles or not all(text(x.get(k)) for k in ('short','balanced','detailed')) or not sources(x.get('sources')) for x in c['notes'])): raise ValueError('Ungültige Lernzettel')
    if (not unique(c['cards'], 'id') or any(x.get('topic') not in titles or not all(text(x.get(k)) for k in ('front','back')) or not sources(x.get('sources')) for x in c['cards'])): raise ValueError('Ungültige Karten')
    if (not unique(c['cases'], 'id') or any(not all(text(x.get(k)) for k in ('published','text')) or not sources(x.get('sources')) or not isinstance(x.get('diseases'),list) or not x['diseases'] or not all(text(t) for t in x['diseases']) or not isinstance(x.get('questions'),list) or not x['questions'] or any(not isinstance(q,dict) or not text(q.get('question')) or not text(q.get('answer')) or not sources(q.get('sources')) for q in x['questions']) for x in c['cases'])): raise ValueError('Ungültige Fallbeispiele')
    if (not unique(c['quizzes'], 'id') or any(x.get('topic') not in titles or not all(text(x.get(k)) for k in ('published','question','explanation')) or not sources(x.get('sources')) or not isinstance(x.get('options'),list) or len(x['options']) != 4 or not all(text(t) for t in x['options']) or len(set(x['options'])) != 4 or type(x.get('correctIndex')) is not int or x['correctIndex'] not in range(4) for x in c['quizzes'])): raise ValueError('Ungültige Quizfragen')
    return c


def read_remote(http, url):
    response = http.get(url + '/content', headers={'Cache-Control':'no-cache'}, timeout=60, stream=True, allow_redirects=False)
    if response.status_code == 404:
        response.close()
        return None
    if response.status_code != 200:
        raise ValueError(f'Katalogabruf fehlgeschlagen (HTTP {response.status_code})')
    data = bytearray()
    try:
        for chunk in response.iter_content(65536):
            data.extend(chunk)
            if len(data) > MAX_CATALOG: raise ValueError('Serverkatalog zu groß')
    finally:
        response.close()
    catalog = validate_base(json.loads(data))
    report_capacity(len(data), 'server')
    return catalog


def sync(http, url, bundled, folder, drive, token, cards_folder="", cases_folder="", check_only=False):
    url = origin(url)
    if not re.fullmatch(r'[A-Za-z0-9_-]+', folder): raise ValueError('Ungültige Ordner-ID')
    if len(token) < 32: raise ValueError('Publikations-Secret nicht eingerichtet')
    bundled = validate_base(bundled)
    remote = read_remote(http, url)
    if remote is not None and (remote['revision'] < bundled['revision'] or
                               (remote['revision'] == bundled['revision'] and remote != bundled)):
        raise ValueError('Serverstand widerspricht dem mitgelieferten Katalog; Veröffentlichung abgebrochen')
    base = remote if remote is not None else bundled
    before = list_folder(drive, folder)
    def extract(f):
        value = download_text(drive, f)
        if not re.search(r'(?im)^QUELLEN(?:[ \t]+UND[ \t]+[^\r\n]{1,80})?[ \t]*$', value):
            raise ValueError(f"Quellenabschnitt fehlt: {f['name']}")
        return value
    payload = assemble(base, before, extract)
    candidate = import_practice(json.loads(payload), drive, cards_folder, cases_folder)
    payload = (json.dumps(candidate, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8')
    if len(payload) > MAX_CATALOG: raise ValueError('Katalog überschreitet das 8-MB-Limit')
    report_capacity(len(payload), 'notes_candidate')
    candidate = validate_base(candidate)
    if before != list_folder(drive, folder):
        raise ValueError('Drive-Ordner während des Imports geändert; kein Update veröffentlicht')
    unchanged = remote is not None and all(candidate[k] == remote[k] for k in ('notes', 'cards', 'cases'))
    if check_only:
        return 'checked-unchanged' if unchanged else 'checked-changed'
    if unchanged:
        return 'unchanged'
    response = http.put(url + '/admin/content', data=payload,
        headers={'Authorization':'Bearer ' + token, 'Content-Type':'application/json', 'If-Match':str(remote['revision'] if remote else 0)},
        timeout=60, allow_redirects=False)
    if response.status_code == 409: raise ValueError('Serverkatalog wurde parallel geändert; kein Update veröffentlicht')
    if response.status_code != 200: raise ValueError(f'Veröffentlichung fehlgeschlagen (HTTP {response.status_code})')
    return 'published'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--folder', required=True)
    parser.add_argument('--worker', required=True, help='Öffentliche HTTPS-Origin des Inhalts-Workers')
    parser.add_argument('--bundled', type=Path, default=Path('app/src/main/assets/learning_content.json'))
    args = parser.parse_args()
    import requests
    import google.auth
    from google.auth.transport.requests import AuthorizedSession
    credentials, _ = google.auth.default(scopes=['https://www.googleapis.com/auth/drive.readonly'])
    result = sync(requests.Session(), args.worker, json.loads(args.bundled.read_text(encoding='utf-8')),
                  args.folder, AuthorizedSession(credentials), os.environ.get('CONTENT_PUBLISH_TOKEN',''),
                  os.environ.get('PRACTICE_CARDS_FOLDER_ID', ''), os.environ.get('PRACTICE_CASES_FOLDER_ID', ''))
    print('Aktualisierter Katalog veröffentlicht.' if result == 'published' else 'Keine Änderung des Inhaltskatalogs.')


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        # GitHub annotations are accessible even where downloaded logs are blocked.
        # Only expose our own predictable validation messages; never credentials or HTTP bodies.
        known = ('Fehlende PDFs:', 'Unbekannter Lernzettel:', 'Mehrdeutige Datei:',
                 'Falscher Typ oder unzulässige Größe:', 'Keine lesbare Textschicht:',
                 'Quellenabschnitt fehlt:', 'Unterordner Schriftliches Examen',
                 'Katalogabruf fehlgeschlagen', 'Veröffentlichung fehlgeschlagen',
                 'Serverstand widerspricht', 'Ungültiger Basiskatalog',
                 'Drive-Ordner während des Imports geändert', 'Dateiinhalt während des Imports geändert')
        msg = str(exc)
        if not (type(exc) is ValueError and msg.startswith(known)):
            status = getattr(getattr(exc, 'response', None), 'status_code', None)
            msg = type(exc).__name__ + (f' (HTTP {status})' if isinstance(status, int) else '')
        msg = msg.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
        print(f'::error title=Lernzettel-Import::{msg}', flush=True)
        raise

