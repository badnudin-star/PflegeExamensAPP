#!/usr/bin/env python3
"""Check the deployed ranking without creating participants or displaying quiz content."""
import json
import os
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.request import Request, build_opener
from readiness import NoRedirect
from ranking_initialize import DB_ID
from ranking_provision import PreflightError, WORKER, request

ORIGIN = 'https://pflege-examen-ranking.badnudin.workers.dev'
MAX = 1024 * 1024


def get(opener, path):
    # Match the existing catalog synchronization probe's User-Agent: the
    # default urllib agent is rejected by some Cloudflare edge configurations.
    req = Request(ORIGIN + path, headers={'Accept':'application/json', 'Cache-Control':'no-cache',
                                          'User-Agent':'python-requests/2.32.3'}, method='GET')
    try:
        response = opener.open(req, timeout=30)
    except HTTPError as exc:
        if exc.code != 404: raise PreflightError(f'{path}: HTTP {exc.code}') from None
        response = exc
    with response:
        status = response.status
        raw = response.read(MAX + 1)
    if len(raw) > MAX: raise PreflightError('response too large')
    value = json.loads(raw)
    if not isinstance(value, dict): raise PreflightError('invalid response')
    return status, value


def run(env, opener):
    token, account = env.get('CLOUDFLARE_API_TOKEN', '').strip(), env.get('CLOUDFLARE_ACCOUNT_ID', '').strip()
    if not token or not re.fullmatch(r'[0-9a-fA-F]{32}', account): raise PreflightError('invalid configuration')
    base = 'https://api.cloudflare.com/client/v4/accounts/' + account
    try:
        settings = request(opener, base, token, '/workers/scripts/' + WORKER + '/settings').get('result')
    except HTTPError as exc:
        raise PreflightError(f'Cloudflare-Worker-Einstellungen: HTTP {exc.code}') from None
    bindings = settings.get('bindings') if isinstance(settings, dict) else None
    if not isinstance(bindings, list): raise PreflightError('worker bindings missing')
    def binding(name):
        matches = [v for v in bindings if isinstance(v,dict) and v.get('name') == name]
        if len(matches) != 1: raise PreflightError('worker binding missing or duplicated')
        return matches[0]
    db = binding('DB')
    if db.get('type') != 'd1' or db.get('id', db.get('database_id')) != DB_ID: raise PreflightError('wrong database binding')
    service = binding('QUIZ_CATALOG_SERVICE')
    if service.get('type') != 'service' or service.get('service') != 'pflegeexamen-content': raise PreflightError('wrong catalog binding')
    for name, ident, limit in [('REGISTRATION_RATE','74751001',5),('READ_RATE','74751002',120),('AUTH_RATE','74751003',60),('ACCOUNT_RATE','74751004',20)]:
        b = binding(name)
        if b.get('type') != 'ratelimit' or str(b.get('namespace_id')) != ident or not isinstance(b.get('simple'), dict) or b['simple'].get('limit') != limit or b['simple'].get('period') != 60:
            raise PreflightError('rate limit binding mismatch')
    status, health = get(opener, '/health')
    if status != 200 or health.get('ok') is not True or health.get('service') != 'quiz-ranking-draft': raise PreflightError('health check failed')
    status, board = get(opener, '/leaderboard')
    if (status != 200 or type(board.get('generatedAtMillis')) is not int or
        type(board.get('nextUpdateAtMillis')) is not int or board['generatedAtMillis'] <= 0 or
        board['nextUpdateAtMillis'] <= board['generatedAtMillis'] or type(board.get('totalParticipants')) is not int or
        not isinstance(board.get('entries'), list) or len(board['entries']) > 100 or board['totalParticipants'] < len(board['entries'])):
        raise PreflightError('leaderboard check failed')
    print('::notice title=Ranglisten-Dienst::Datenbank-, Katalog- und Rate-Limit-Bindings sowie öffentliche Ranglistenabfrage geprüft.')
    status, quiz = get(opener, '/quiz/today')
    if status == 404 and quiz.get('error') == 'Heute ist noch kein Quiz veröffentlicht.':
        print('::warning title=Heutiges Wertungsquiz::Heute ist kein Quiz veröffentlicht; Abgabe kann derzeit nicht getestet werden.')
    elif status == 200 and isinstance(quiz.get('questions'), list) and len(quiz['questions']) == 50 and isinstance(quiz.get('fingerprint'),str):
        print('::notice title=Heutiges Wertungsquiz::Das Quiz wird mit 50 Fragen geladen; keine Antworten wurden abgegeben.')
    else:
        raise PreflightError('quiz check failed')


def main():
    try: run(os.environ, build_opener(NoRedirect()))
    except HTTPError as exc:
        print(f'::error::Prüfung fehlgeschlagen (HTTP {exc.code}); Rangliste nicht als funktionsfähig bestätigen.')
        return 1
    except PreflightError as exc:
        print(f'::error::Prüfung fehlgeschlagen: {exc}; Rangliste nicht als funktionsfähig bestätigen.')
        return 1
    except (ValueError, KeyError, TypeError, OSError, URLError):
        print('::error::Prüfung fehlgeschlagen oder Antwort ungültig; Rangliste nicht als funktionsfähig bestätigen.')
        return 1
    return 0

if __name__ == '__main__': sys.exit(main())
