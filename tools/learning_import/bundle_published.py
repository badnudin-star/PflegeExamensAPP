#!/usr/bin/env python3
"""Bundle a validated public catalog into an APK build without publishing anything."""
import argparse
import json
from pathlib import Path
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
from urllib.error import HTTPError

from import_notes import MAX_CATALOG, TOPICS, save_atomic
from sync_drive import validate_base
from catalog_capacity import report_capacity


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def validate_endpoint(value):
    url = urlsplit(value)
    if (url.scheme != 'https' or not url.hostname or url.username or url.password or
            url.path != '/content' or url.query or url.fragment):
        raise ValueError('CONTENT_URL muss eine öffentliche HTTPS-Adresse mit Pfad /content sein')
    return value


def validate_snapshot(raw, bundled):
    if len(raw) > MAX_CATALOG:
        raise ValueError('Serverkatalog überschreitet das Größenlimit')
    try:
        remote = validate_base(json.loads(raw.decode('utf-8')))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError('Serverkatalog ist kein gültiges UTF-8-JSON') from exc
    bundled = validate_base(bundled)
    if remote['revision'] < bundled['revision']:
        raise ValueError('Serverkatalog ist älter als der mitgelieferte Stand')
    if remote['revision'] == bundled['revision'] and remote != bundled:
        raise ValueError('Serverkatalog widerspricht dem mitgelieferten Stand bei gleicher Revision')
    titles = {title for _, title in TOPICS}
    if {note['topic'] for note in remote['notes']} != titles or any(
            type(note.get('formatVersion')) is not int or note['formatVersion'] != 2 for note in remote['notes']):
        raise ValueError('Die beiden aktuellen Lernzettel-Versionen fehlen für mindestens ein Thema')
    return remote


def download(url):
    request = Request(validate_endpoint(url), headers={'Cache-Control': 'no-cache', 'Accept': 'application/json', 'User-Agent': 'python-requests/2.32.3'})
    with build_opener(NoRedirect()).open(request, timeout=60) as response:
        if response.status != 200:
            raise ValueError(f'Katalogabruf fehlgeschlagen (HTTP {response.status})')
        return response.read(MAX_CATALOG + 1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', required=True)
    parser.add_argument('--asset', type=Path, default=Path('app/src/main/assets/learning_content.json'))
    args = parser.parse_args()
    baseline = json.loads(args.asset.read_text(encoding='utf-8'))
    data = download(args.url)
    catalog = validate_snapshot(data, baseline)
    report_capacity(len(data), 'bundle')
    # Atomic replacement in the CI workspace only. Never modify server or Drive.
    save_atomic(args.asset, data)
    print(f"Offline-Katalog Revision {catalog['revision']}: {len(catalog['notes'])} Themen, "
          f"{len(catalog['cards'])} Karten, {len(catalog['cases'])} Fälle, {len(catalog['quizzes'])} Quizfragen.")


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        # Do not disclose URL, server response bodies, or configuration values.
        known = ('CONTENT_URL muss', 'Serverkatalog', 'Die beiden aktuellen Lernzettel',
                 'Katalogabruf fehlgeschlagen', 'Ungültiger', 'Ungültige')
        if type(exc) is ValueError and str(exc).startswith(known):
            message = str(exc)
        elif isinstance(exc, HTTPError):
            message = f'Katalogabruf fehlgeschlagen (HTTP {exc.code})'
        else:
            message = type(exc).__name__
        print('::error title=Offline-Katalog::' + message.replace('%', '%25').replace('\n', '%0A'), flush=True)
        raise
