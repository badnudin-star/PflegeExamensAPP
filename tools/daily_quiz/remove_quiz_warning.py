#!/usr/bin/env python3
"""Remove the warning only from an exactly matching published quiz day."""
import argparse
import copy
import json
import os
from pathlib import Path
import sys

from publish_unreviewed import prepare, MAX_DRAFT_BYTES
from import_notes import MAX_CATALOG
from sync_drive import origin, read_remote, validate_base


def replacement(remote, raw, expected_hash):
    expected = prepare(raw, expected_hash, 'true')
    original = json.loads(raw)
    validate_base(remote)
    day = original['date']
    current = [q for q in remote['quizzes'] if q['published'] == day]
    expected_by_id = {q['id']: q for q in expected['quizzes']}
    if len(current) != len(expected_by_id) or {q['id']: q for q in current} != expected_by_id:
        raise ValueError('Der aktuelle Quiztag entspricht nicht exakt dem markierten Entwurf; keine Änderung')
    originals = {q['id']: q for q in original['quizzes']}
    result = copy.deepcopy(remote)
    result['revision'] += 1
    result['quizzes'] = [copy.deepcopy(originals[q['id']]) if q['published'] == day else q for q in result['quizzes']]
    validate_base(result)
    return day, result


def archive_absent(http, worker, day):
    response = http.get(worker + '/quiz-archive/' + day, headers={'Cache-Control': 'no-cache'},
                        timeout=60, allow_redirects=False)
    try:
        return response.status_code == 404
    finally:
        response.close()


def remove(http, worker, token, raw, expected_hash, confirmed):
    if confirmed != 'true':
        raise ValueError('Entfernung nicht bestätigt; keine Anfrage')
    # Validate original and hash before any network call.
    expected = prepare(raw, expected_hash, 'true')
    day = expected['date']
    worker = origin(worker)
    if len(token) < 32:
        raise ValueError('Publikations-Secret fehlt')
    remote = read_remote(http, worker)
    if remote is None:
        raise ValueError('Kein aktiver Katalog; keine Änderung')
    _, candidate = replacement(remote, raw, expected_hash)
    if not archive_absent(http, worker, day):
        raise ValueError('Archivstatus verhindert Änderung; keine Änderung vorgenommen')
    payload = (json.dumps(candidate, ensure_ascii=False, separators=(',', ':')) + '\n').encode()
    if len(payload) > MAX_CATALOG:
        raise ValueError('Katalog erreicht Größenlimit; keine Änderung')
    try:
        r = http.put(worker + '/admin/content', data=payload, headers={
            'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json',
            'If-Match': str(remote['revision'])}, timeout=60, allow_redirects=False)
    except Exception as exc:
        raise RuntimeError('Aktualisierung nicht bestätigt; Zustand prüfen, nicht blind wiederholen') from exc
    try:
        if r.status_code == 409:
            raise ValueError('Katalog gleichzeitig geändert; keine Änderung vorgenommen')
        if r.status_code != 200:
            raise RuntimeError('Aktualisierung nicht bestätigt; Zustand prüfen, nicht blind wiederholen')
    finally:
        r.close()
    # Never repeat a write after an ambiguous failure. Read back only.
    try:
        after = read_remote(http, worker)
        not_archived = archive_absent(http, worker, day)
    except Exception as exc:
        raise RuntimeError('Änderung angenommen, aber Rückleseprüfung fehlgeschlagen; nicht blind wiederholen') from exc
    if after != candidate or not not_archived:
        raise RuntimeError('Änderung angenommen, aber Rückleseprüfung nicht eindeutig; nicht blind wiederholen')
    return len(expected['quizzes']), candidate['revision']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--draft', required=True, type=Path)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--worker', required=True)
    args = parser.parse_args()
    if args.draft.stat().st_size > MAX_DRAFT_BYTES:
        raise ValueError('Entwurf zu groß')
    import requests
    count, revision = remove(requests, args.worker, os.environ.get('CONTENT_PUBLISH_TOKEN', ''),
                             args.draft.read_bytes(), args.sha256, os.environ.get('REMOVAL_CONFIRMED', ''))
    print(f'::notice title=Quiz-Aktualisierung::{count} Fragen aktualisiert und zurückgelesen; Revision {revision}.')
    print('Die Fragen wurden nicht fachlich geprüft; bestehender Quizfortschritt kann ungültig werden.')


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        # Never echo server bodies, credentials, source content, URLs or tracebacks.
        fixed = str(exc) if type(exc) in (ValueError, RuntimeError) else 'Fehler; Zustand prüfen, nicht blind wiederholen'
        allowed = (
            'Entfernung nicht bestätigt', 'Der aktuelle Quiztag', 'Ungültige Prüfsumme',
            'Entwurf zu groß', 'Kein aktiver Katalog', 'Archivstatus verhindert',
            'Katalog erreicht Größenlimit', 'Katalog gleichzeitig geändert',
            'Publikations-Secret fehlt', 'Aktualisierung nicht bestätigt', 'Änderung angenommen',
        )
        print('::error title=Quiz-Aktualisierung::' + (fixed if fixed.startswith(allowed) else 'Fehler; Zustand prüfen, nicht blind wiederholen'))
        sys.exit(1)
