#!/usr/bin/env python3
"""Publish today's technically validated but clinically unreviewed draft, without replacing a day."""
import argparse
import copy
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import sys
from zoneinfo import ZoneInfo

import quiz_pipeline as qp

MAX_DRAFT_BYTES = 4 * 1024 * 1024
BERLIN = ZoneInfo('Europe/Berlin')


def now_berlin():
    return dt.datetime.now(BERLIN)


def check_date(draft, clock):
    now = clock()
    if now.tzinfo is None:
        raise ValueError('Zeitzone fehlt')
    now = now.astimezone(BERLIN)
    if draft['date'] != now.date().isoformat() or now.time() >= dt.time(23, 55):
        raise ValueError('Entwurf nicht für den heutigen Tag oder Veröffentlichung zu nah am Tageswechsel')


def prepare(raw, digest, clock=now_berlin):
    if len(raw) > MAX_DRAFT_BYTES or not re.fullmatch(r'[0-9a-f]{64}', digest):
        raise ValueError('Ungültige Entwurfsdatei oder Prüfsumme')
    if hashlib.sha256(raw).hexdigest() != digest:
        raise ValueError('Prüfsumme stimmt nicht')
    draft = qp.validate_draft(json.loads(raw))
    check_date(draft, clock)
    return draft


def same_day(catalog, draft):
    return [q for q in catalog['quizzes'] if q['published'] == draft['date']] == draft['quizzes']


def publish(http, worker, token, draft, bundled, clock=now_berlin):
    worker = qp.origin(worker)
    qp.validate_draft(draft)
    qp.validate_base(bundled)
    check_date(draft, clock)
    if len(token) < 32:
        raise ValueError('Publikations-Secret fehlt')
    remote = qp.read_remote(http, worker)
    if remote is None:
        raise ValueError('Serverkatalog fehlt; kein Rückfall auf App-Katalog')
    if remote['revision'] < bundled['revision'] or (remote['revision'] == bundled['revision'] and remote != bundled):
        raise ValueError('Serverstand widerspricht dem App-Katalog')
    if any(q['published'] == draft['date'] for q in remote['quizzes']):
        if same_day(remote, draft):
            return 'already-published'
        raise ValueError('Für diesen Tag existieren bereits andere Fragen; keine Überschreibung')

    response = http.get(worker + '/quiz-archive/' + draft['date'], headers={'Cache-Control': 'no-cache'},
                        timeout=30, allow_redirects=False)
    try:
        if response.status_code != 404:
            raise ValueError('Archivstatus nicht leer oder nicht verfügbar; keine Veröffentlichung')
    finally:
        response.close()
    candidate = copy.deepcopy(remote)
    candidate['revision'] += 1
    candidate['quizzes'].extend(draft['quizzes'])
    qp.validate_base(candidate)
    payload = (json.dumps(candidate, ensure_ascii=False, separators=(',', ':')) + '\n').encode()
    qp.report_capacity(len(payload), 'quiz_candidate')
    if len(payload) > qp.MAX_CATALOG:
        raise ValueError('Katalog erreicht Größenlimit; Archivierung notwendig')
    check_date(draft, clock)  # Never publish an expired draft after a queued or delayed run.
    try:
        response = http.put(worker + '/admin/content', data=payload,
                            headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json',
                                     'If-Match': str(remote['revision'])},
                            timeout=60, allow_redirects=False)
        status = response.status_code
        response.close()
    except Exception:
        # The server may already have committed. Do not resend blindly.
        status = None
    try:
        actual = qp.read_remote(http, worker)
    except Exception:
        raise ValueError('Rückleseprüfung fehlgeschlagen; Ergebnis ungeklärt, nicht blind wiederholen') from None
    if actual is None or not same_day(actual, draft):
        raise ValueError('Veröffentlichung nicht durch Rücklesen bestätigt; Zustand prüfen, nicht blind wiederholen')
    if status != 200:
        return 'confirmed-after-uncertain-response'
    return 'published'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--draft', type=Path, required=True)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--worker', required=True)
    parser.add_argument('--bundled', type=Path, default=Path('app/src/main/assets/learning_content.json'))
    args = parser.parse_args()
    if args.draft.stat().st_size > MAX_DRAFT_BYTES:
        raise ValueError('Entwurf zu groß')
    draft = prepare(args.draft.read_bytes(), args.sha256)
    bundled = json.loads(args.bundled.read_text(encoding='utf-8'))
    import requests
    result = publish(requests, args.worker, os.environ.get('CONTENT_PUBLISH_TOKEN', ''), draft, bundled)
    print('::notice title=Automatisches Tagesquiz::Status ' + result + '; 50 Fragen technisch geprüft, fachlich ungeprüft.')


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        # Only fixed messages from this script, never credentials, bodies or prompts.
        safe = {
            'Entwurf nicht für den heutigen Tag oder Veröffentlichung zu nah am Tageswechsel',
            'Ungültige Entwurfsdatei oder Prüfsumme', 'Prüfsumme stimmt nicht',
            'Publikations-Secret fehlt', 'Serverkatalog fehlt; kein Rückfall auf App-Katalog',
            'Serverstand widerspricht dem App-Katalog',
            'Für diesen Tag existieren bereits andere Fragen; keine Überschreibung',
            'Archivstatus nicht leer oder nicht verfügbar; keine Veröffentlichung',
            'Katalog erreicht Größenlimit; Archivierung notwendig',
            'Rückleseprüfung fehlgeschlagen; Ergebnis ungeklärt, nicht blind wiederholen',
            'Veröffentlichung nicht durch Rücklesen bestätigt; Zustand prüfen, nicht blind wiederholen',
        }
        message = str(exc) if type(exc) is ValueError and str(exc) in safe else 'Technische Prüfung oder Netzwerkzugriff fehlgeschlagen'
        print('::error title=Automatisches Tagesquiz::' + message + '. Serverstand vor Wiederholung prüfen.', file=sys.stderr)
        sys.exit(1)
