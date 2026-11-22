#!/usr/bin/env python3
"""Copy or safely remove one old quiz day with a separately verified local backup.

Explicit operator action only. Does not generate or approve questions. A prune can
make old app versions lose access to that day: migrate and test clients first.
"""
import argparse
import json
import os
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'learning_import'))
from import_notes import MAX_CATALOG
from sync_drive import origin, read_remote, validate_base
from quiz_archive import strict_catalog, verify

MAX_DAY = 1024 * 1024


def day_questions(catalog, day):
    import datetime
    try:
        if datetime.date.fromisoformat(day).isoformat() != day:
            raise ValueError()
    except (TypeError, ValueError):
        raise ValueError('Ungültiges Quizdatum') from None
    questions = [q for q in catalog['quizzes'] if q['published'] == day]
    if not questions:
        raise ValueError('Quiztag fehlt im Ausgangskatalog')
    return questions


def checked_backup(raw, backup, day):
    # An independently stored copy is still required; a digest is not authenticity.
    verify(raw, backup)
    catalog = strict_catalog(raw)
    return catalog, day_questions(catalog, day)


def fetch_archived(http, worker, day):
    response = http.get(worker + '/quiz-archive/' + day, headers={'Cache-Control':'no-cache'}, timeout=60, stream=True, allow_redirects=False)
    if response.status_code != 200:
        response.close()
        raise ValueError('Archivkopie nicht abrufbar')
    data = bytearray()
    try:
        for chunk in response.iter_content(65536):
            data.extend(chunk)
            if len(data) > MAX_DAY:
                raise ValueError('Archivkopie zu groß')
    finally:
        response.close()
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result: raise ValueError('Doppelter JSON-Schlüssel in Archivkopie')
            result[key] = value
        return result
    def invalid_number(_): raise ValueError('Ungültige JSON-Zahl')
    return json.loads(data, object_pairs_hook=unique, parse_constant=invalid_number)


def operate(http, worker, token, raw, backup, day, mode, confirm_compatibility=False):
    worker = origin(worker)
    if len(token) < 32: raise ValueError('Publikations-Secret fehlt')
    if mode not in ('copy', 'prune'): raise ValueError('Ungültiger Modus')
    if mode == 'prune' and not confirm_compatibility:
        raise ValueError('Kompatibilität älterer App-Versionen nicht bestätigt')
    catalog, questions = checked_backup(raw, backup, day)
    current = read_remote(http, worker)
    if current != catalog:
        raise ValueError('Serverstand entspricht nicht dem gesicherten Ausgangskatalog')
    if mode == 'copy':
        response = http.post(worker + '/admin/quiz-archive/' + day,
            headers={'Authorization':'Bearer ' + token, 'If-Match':str(catalog['revision'])}, timeout=60, allow_redirects=False)
        if response.status_code not in (200, 201): raise ValueError('Archivierung abgelehnt oder fehlgeschlagen')
    if fetch_archived(http, worker, day) != {'date':day, 'quizzes':questions}:
        raise ValueError('Archivkopie stimmt nicht mit der unabhängigen Sicherung überein')
    if mode == 'prune':
        candidate = dict(catalog)
        candidate['revision'] += 1
        candidate['quizzes'] = [q for q in catalog['quizzes'] if q['published'] != day]
        validate_base(candidate)
        payload = (json.dumps(candidate, ensure_ascii=False, separators=(',', ':')) + '\n').encode()
        if len(payload) > MAX_CATALOG: raise ValueError('Katalogkandidat zu groß')
        response = http.put(worker + '/admin/content', data=payload,
            headers={'Authorization':'Bearer ' + token, 'Content-Type':'application/json', 'If-Match':str(catalog['revision'])}, timeout=60, allow_redirects=False)
        if response.status_code != 200: raise ValueError('Entfernung abgelehnt oder fehlgeschlagen; Serverstand prüfen')
    return len(questions)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['copy', 'prune'])
    parser.add_argument('--worker', required=True)
    parser.add_argument('--snapshot', type=Path, required=True)
    parser.add_argument('--backup', type=Path, required=True)
    parser.add_argument('--date', required=True)
    parser.add_argument('--confirm-older-apps-cannot-access', action='store_true')
    args = parser.parse_args()
    with args.snapshot.open('rb') as file: raw = file.read(MAX_CATALOG + 1)
    import requests
    count = operate(requests.Session(), args.worker, os.environ.get('CONTENT_PUBLISH_TOKEN', ''), raw, args.backup, args.date, args.mode, args.confirm_older_apps_cannot_access)
    print(f'{count} Fragen ' + ('in das Archiv kopiert und überprüft.' if args.mode == 'copy' else 'aus dem aktiven Katalog entfernt; Archivkopie bleibt erhalten.'))


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        # Never print HTTP bodies, tokens, quiz texts, or source excerpts.
        print('Archivoperation fehlgeschlagen: ' + type(exc).__name__, file=sys.stderr)
        sys.exit(1)
