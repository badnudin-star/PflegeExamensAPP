#!/usr/bin/env python3
"""Read-only daily preflight; skip expensive generation when the day already exists."""
import datetime as dt
import os
from pathlib import Path
import sys
from zoneinfo import ZoneInfo

import quiz_pipeline as qp


def check(http, worker, now):
    if now.tzinfo is None:
        raise ValueError('Zeitzone fehlt')
    local = now.astimezone(ZoneInfo('Europe/Berlin'))
    if local.time() >= dt.time(23, 55):
        raise ValueError('Zu nah am Tageswechsel')
    day = local.date().isoformat()
    worker = qp.origin(worker)
    catalog = qp.read_remote(http, worker)
    if catalog is None:
        raise ValueError('Serverkatalog fehlt')
    count = sum(q['published'] == day for q in catalog['quizzes'])
    if count == 50:
        return True
    if count:
        raise ValueError('Unvollständiger Quiztag bereits vorhanden')
    response = http.get(worker + '/quiz-archive/' + day, headers={'Cache-Control':'no-cache'},
                        timeout=30, allow_redirects=False)
    try:
        if response.status_code != 404:
            raise ValueError('Archivstatus nicht leer oder nicht verfügbar')
    finally:
        response.close()
    return False


def main():
    import requests
    skipped = check(requests, os.environ.get('CONTENT_WORKER_ORIGIN', ''),
                    dt.datetime.now(ZoneInfo('Europe/Berlin')))
    with Path(os.environ['GITHUB_OUTPUT']).open('a', encoding='utf-8') as f:
        f.write('skip=' + str(skipped).lower() + '\n')
    print('::notice title=Tagesquiz::' + ('50 Fragen für den Tag bereits vorhanden; keine neue Generierung.' if skipped else 'Noch kein Quiztag vorhanden; Generierung kann beginnen.'))


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('::error title=Tagesquiz::Vorprüfung fehlgeschlagen; keine Fragen erzeugt oder veröffentlicht.', file=sys.stderr)
        sys.exit(1)
