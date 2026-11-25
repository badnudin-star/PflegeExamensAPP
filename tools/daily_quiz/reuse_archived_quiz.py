#!/usr/bin/env python3
"""Publish a guarded copy of one archived past quiz as today's quiz, once.

This is a real publication visible to app users, not an isolated test. It never
changes the archive, removes old questions, or replaces today's existing quiz.
"""
import argparse
import copy
import datetime as dt
import json
import os
from pathlib import Path
import re
import sys
from zoneinfo import ZoneInfo

import publish_automatic as auto
import quiz_pipeline as qp

MAX_ARCHIVE = 4 * 1024 * 1024


def copy_for_today(http, worker, source_day, clock=auto.now_berlin):
    if not isinstance(source_day, str) or not re.fullmatch(r'\d{4}-\d{2}-\d{2}', source_day):
        raise ValueError('Ungültiger Archivtag')
    try:
        parsed = dt.date.fromisoformat(source_day)
    except ValueError:
        raise ValueError('Ungültiger Archivtag') from None
    if parsed.isoformat() != source_day:
        raise ValueError('Ungültiger Archivtag')
    now = clock()
    if now.tzinfo is None:
        raise ValueError('Zeitzone fehlt')
    now = now.astimezone(ZoneInfo('Europe/Berlin'))
    if parsed >= now.date() or now.time() >= dt.time(23, 55):
        raise ValueError('Archivtag ist nicht vergangen oder Tageswechsel steht bevor')
    target = now.date().isoformat()
    worker = qp.origin(worker)
    response = http.get(worker + '/quiz-archive/' + source_day, headers={'Cache-Control':'no-cache'},
                        timeout=30, allow_redirects=False, stream=True)
    try:
        if response.status_code != 200:
            raise ValueError('Archivtag nicht verfügbar')
        raw = bytearray()
        for chunk in response.iter_content(65536):
            raw.extend(chunk)
            if len(raw) > MAX_ARCHIVE:
                raise ValueError('Archivtag zu groß')
    finally:
        response.close()
    source = json.loads(raw)
    if not isinstance(source, dict) or set(source) != {'date', 'quizzes'} or source['date'] != source_day:
        raise ValueError('Archivtag ungültig')
    qp.validate_draft(source)
    target_draft = copy.deepcopy(source)
    target_draft['date'] = target
    for i, q in enumerate(target_draft['quizzes'], 1):
        q['id'] = f'quiz-{target}-{i:02d}'
        q['published'] = target
    qp.validate_draft(target_draft)
    auto.check_date(target_draft, clock)
    return target_draft


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-date', required=True)
    parser.add_argument('--worker', required=True)
    parser.add_argument('--bundled', type=Path, default=Path('app/src/main/assets/learning_content.json'))
    args = parser.parse_args()
    if os.environ.get('CONFIRMED') != 'true':
        raise ValueError('Veröffentlichung nicht bestätigt')
    import requests
    draft = copy_for_today(requests, args.worker, args.source_date)
    bundled = json.loads(args.bundled.read_text(encoding='utf-8'))
    result = auto.publish(requests, args.worker, os.environ.get('CONTENT_PUBLISH_TOKEN', ''), draft, bundled)
    print(f'::notice title=Quizkopie::{args.source_date} als {draft["date"]}: {result}. 50 Fragen ohne fachliche Prüfung; Archiv unverändert.')


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        safe = {
            'Ungültiger Archivtag', 'Zeitzone fehlt',
            'Archivtag ist nicht vergangen oder Tageswechsel steht bevor',
            'Archivtag nicht verfügbar', 'Archivtag zu groß', 'Archivtag ungültig',
            'Veröffentlichung nicht bestätigt', 'Publikations-Secret fehlt',
            'Serverkatalog fehlt; kein Rückfall auf App-Katalog',
            'Serverstand widerspricht dem App-Katalog',
            'Für diesen Tag existieren bereits andere Fragen; keine Überschreibung',
            'Archivstatus nicht leer oder nicht verfügbar; keine Veröffentlichung',
            'Katalog erreicht Größenlimit; Archivierung notwendig',
            'Entwurf nicht für den heutigen Tag oder Veröffentlichung zu nah am Tageswechsel',
            'Rückleseprüfung fehlgeschlagen; Ergebnis ungeklärt, nicht blind wiederholen',
            'Veröffentlichung nicht durch Rücklesen bestätigt; Zustand prüfen, nicht blind wiederholen',
        }
        reason = str(exc) if type(exc) is ValueError and str(exc) in safe else 'Technische Prüfung oder Netzwerkzugriff fehlgeschlagen'
        print('::error title=Quizkopie::' + reason + '. Katalog und Archiv vor Wiederholung prüfen.', file=sys.stderr)
        sys.exit(1)
