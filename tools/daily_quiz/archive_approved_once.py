"""Copy the specifically approved quiz day, pinned to an independently backed-up snapshot.

No pruning, arbitrary destination, date input, secret output, or automatic retry.
The external encrypted snapshot is independently retained by the operator.
"""
import json
import hashlib
import os
import time
from datetime import date, datetime
from urllib.error import HTTPError
from urllib.request import HTTPRedirectHandler, Request, build_opener
from zoneinfo import ZoneInfo

from quiz_archive import strict_catalog

BASE = 'https://pflegeexamen-content.badnudin.workers.dev'
DAY = '2026-11-22'
REVISION = 7
QUESTIONS = 50
SNAPSHOT_SHA256 = '955265bd5e994d42858fcb6a9b8a54fa4e73d55950c8de911778bd7519ed0560'
MAX_CONTENT = 8 * 1024 * 1024
MAX_DAY = 1024 * 1024


class Stop(Exception):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def no_duplicates(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate key')
        result[key] = value
    return result


def read_response(opener, request, maximum):
    try:
        with opener.open(request, timeout=60) as response:
            status = response.status
            body = response.read(maximum + 1)
    except HTTPError as error:
        status = error.code
        body = error.read(maximum + 1)
    if len(body) > maximum:
        raise Stop('Antwort zu groß; Ergebnis prüfen.')
    return status, body


def request(path, method='GET', token=None, revision=None):
    headers = {'User-Agent': 'Mozilla/5.0', 'Cache-Control': 'no-cache'}
    if token is not None:
        headers['Authorization'] = 'Bearer ' + token
    if revision is not None:
        headers['If-Match'] = str(revision)
    return Request(BASE + path, method=method, headers=headers,
                   data=b'' if method == 'POST' else None)


def parse_json(body):
    return json.loads(body, object_pairs_hook=no_duplicates,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError('nonfinite')))


def archived(opener, expected):
    status, body = read_response(opener, request('/quiz-archive/' + DAY), MAX_DAY)
    try:
        value = parse_json(body)
    except (ValueError, TypeError):
        raise Stop('Archivstand nicht eindeutig oder weicht ab; keine Wiederholung.') from None
    if status == 404 and value == {'error': 'Quiztag nicht gefunden.'}:
        return False
    if status != 200 or value != expected:
        raise Stop('Archivstand nicht eindeutig oder weicht ab; keine Wiederholung.')
    return True


def run(token, opener=None, *, expected_hash=SNAPSHOT_SHA256,
        expected_revision=REVISION, expected_count=QUESTIONS, today=None):
    if not isinstance(token, str) or len(token) < 32 or '\n' in token or '\r' in token:
        raise Stop('Veröffentlichungstoken fehlt oder ist ungültig.')
    opener = opener or build_opener(NoRedirect())
    today = today or datetime.now(ZoneInfo('Europe/Berlin')).date()
    if date.fromisoformat(DAY) >= today:
        raise Stop('Quiztag ist noch nicht vergangen; nichts archiviert.')

    status, raw = read_response(
        opener, request('/content?archivpruefung=' + str(time.time_ns())), MAX_CONTENT
    )
    if status != 200 or hashlib.sha256(raw).hexdigest() != expected_hash:
        raise Stop('Katalog stimmt nicht mit der geprüften Sicherung überein; nichts archiviert.')
    catalog = strict_catalog(raw)
    questions = [q for q in catalog['quizzes'] if q['published'] == DAY]
    if catalog['revision'] != expected_revision or len(questions) != expected_count:
        raise Stop('Revision oder Fragenzahl weicht ab; nichts archiviert.')
    expected = {'date': DAY, 'quizzes': questions}

    if archived(opener, expected):
        return 'Archivkopie bereits vorhanden und vollständig geprüft; kein neuer Schreibversuch.'

    try:
        status, body = read_response(
            opener, request('/admin/quiz-archive/' + DAY, 'POST', token, expected_revision),
            MAX_DAY,
        )
    except Exception:
        raise Stop('Antwort auf Archivierung unklar; Archivstand prüfen, nicht erneut starten.') from None
    if status not in (200, 201):
        raise Stop('Archivierung nicht bestätigt; Archivstand prüfen, nicht erneut starten.')
    try:
        confirmation = parse_json(body)
        if confirmation != {'ok': True, 'date': DAY, 'questions': expected_count}:
            raise ValueError('unexpected confirmation')
        if not archived(opener, expected):
            raise ValueError('archive not present')
        status, after = read_response(
            opener, request('/content?archivpruefung=' + str(time.time_ns())), MAX_CONTENT
        )
        if status != 200 or after != raw:
            raise ValueError('catalog changed')
    except Exception:
        raise Stop('Archivierung möglicherweise erfolgt; Archiv und Katalog prüfen, nicht erneut starten.') from None
    return 'Archivkopie vollständig geprüft; Katalog unverändert.'


def main():
    try:
        print(run(os.environ.get('CONTENT_PUBLISH_TOKEN', '')))
        return 0
    except Stop as exc:
        print(str(exc))
    except Exception:
        # An unexpected error may include response bodies or credentials; never print it.
        print('Prüfung fehlgeschlagen; Archivstand prüfen, nicht erneut starten.')
    return 1


if __name__ == '__main__':
    raise SystemExit(main())
