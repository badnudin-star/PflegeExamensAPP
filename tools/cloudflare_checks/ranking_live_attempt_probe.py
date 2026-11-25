#!/usr/bin/env python3
"""Opt-in synthetic production quiz attempt with confirmed account cleanup.

Never prints answers, quiz text, or bearer tokens. This is a write test: a
pseudonymous deletion barrier remains after the synthetic account is deleted.
"""
import datetime as dt
import hashlib
import json
import re
import secrets
import sys
import time
from urllib.error import URLError
from urllib.parse import urlencode
from urllib.request import build_opener
from zoneinfo import ZoneInfo

from readiness import NoRedirect
from ranking_live_probe import get
from ranking_provision import PreflightError
from ranking_live_account_probe import ProbeError, call


def build_attempt(quiz, now):
    if now.tzinfo is None:
        raise ProbeError('Zeitzone fehlt')
    now = now.astimezone(ZoneInfo('Europe/Berlin'))
    if now.time() >= dt.time(23, 55) or not isinstance(quiz, dict):
        raise ProbeError('Quiz oder Testzeit ungültig')
    day, fingerprint, questions = quiz.get('date'), quiz.get('fingerprint'), quiz.get('questions')
    if day != now.date().isoformat() or not isinstance(fingerprint, str) or not re.fullmatch(r'[0-9a-f]{64}', fingerprint):
        raise ProbeError('Quiztag oder Prüfsumme ungültig')
    if not isinstance(questions, list) or len(questions) != 50:
        raise ProbeError('Quiz enthält nicht genau 50 Fragen')
    ids = []
    score = 0
    for q in questions:
        if not isinstance(q, dict):
            raise ProbeError('Quizfrage ungültig')
        ident, correct, options = q.get('id'), q.get('correctIndex'), q.get('options')
        if (not isinstance(ident, str) or not ident.strip() or len(ident) > 200 or
                any(ord(c) < 32 or ord(c) == 127 for c in ident) or
                type(correct) is not int or correct not in range(4) or
                not isinstance(options, list) or len(options) != 4 or
                any(not isinstance(x, str) or not x.strip() for x in options) or len(set(options)) != 4):
            raise ProbeError('Quizfrage ungültig')
        ids.append(ident)
        score += int(correct == 0)
    if len(set(ids)) != 50:
        raise ProbeError('Quiz-IDs nicht eindeutig')
    return {'date': day, 'fingerprint': fingerprint,
            'answers': [{'id': ident, 'option': 0} for ident in ids]}, score


def run(opener, token, username, now, pause=time.sleep):
    # The read preflight happens before creating any test account.
    status, quiz = get(opener, '/quiz/today')
    if status == 404 and quiz.get('error') == 'Heute ist noch kein Quiz veröffentlicht.':
        raise ProbeError('Kein heutiges Quiz; kein Testkonto angelegt')
    if status != 200:
        raise ProbeError('Quizabruf fehlgeschlagen; kein Testkonto angelegt')
    attempt, expected = build_attempt(quiz, now)
    # Must match ranking_live_account_probe.call's exact serialization.
    payload_hash = hashlib.sha256(json.dumps(attempt, separators=(',', ':')).encode()).hexdigest()
    token_hash = hashlib.sha256(token.encode('ascii')).hexdigest()
    print(f'::notice title=Synthetischer Wertungstest::Name={username}; Token-SHA256={token_hash}', flush=True)
    cleanup_confirmed = False
    try:
        status, _, raw = call(opener, 'POST', '/participants', token, {'username': username})
        if status != 201 or json.loads(raw) != {'username': username}:
            raise ProbeError('Registrierung nicht bestätigt')
        status, _, raw = call(opener, 'POST', '/quiz/attempt', token, attempt)
        if status != 201 or json.loads(raw) != {'date': attempt['date'], 'correct': expected, 'total': 50}:
            raise ProbeError('Abgabe oder Wertung nicht bestätigt')
        status, _, raw = call(opener, 'POST', '/quiz/attempt', token, attempt)
        if status != 409 or json.loads(raw) != {'error': 'Dieses Tagesquiz wurde bereits gewertet.'}:
            raise ProbeError('Doppelabgabe wurde nicht eindeutig abgelehnt')
        path = '/quiz/attempt/result?' + urlencode({'date': attempt['date']})
        status, _, raw = call(opener, 'GET', path, token)
        if status != 200 or json.loads(raw) != {'date': attempt['date'], 'correct': expected,
                                                'total': 50, 'payloadHash': payload_hash}:
            raise ProbeError('Ergebnis oder Abgabe-Hash nicht bestätigt')
    finally:
        # A failed/timed-out registration may still have reached the server.
        for i in range(3):
            try:
                status, receipt, _ = call(opener, 'DELETE', '/participants/me', token)
                if status == 204 and receipt == 'v1':
                    cleanup_confirmed = True
                    break
            except (OSError, URLError, ProbeError):
                pass
            if i < 2:
                pause(3)
        if not cleanup_confirmed:
            print(f'::error::Testkontolöschung nicht bestätigt. Testname={username}; Token-SHA256={token_hash}. Verwaltung muss gezielt prüfen.')
            raise ProbeError('Testkontolöschung nicht bestätigt')
    status, _, _ = call(opener, 'GET', '/participants/me', token)
    if status != 404:
        raise ProbeError('Kontolöschung nicht abschließend bestätigt')
    print('::notice title=Ranglisten-Wertungstest::Registrierung, Abgabe, Wertung, Doppelabgabe, Ergebnis-Hash und Kontolöschung geprüft.')


def main():
    try:
        run(build_opener(NoRedirect()), secrets.token_hex(32), 'Probe-' + secrets.token_hex(6),
            dt.datetime.now(ZoneInfo('Europe/Berlin')))
    except (ProbeError, PreflightError, OSError, URLError, ValueError) as exc:
        # Only fixed, non-sensitive categories; never print the response, token,
        # quiz contents, answers, query body, or arbitrary exception text.
        safe_messages = {
            'Kein heutiges Quiz; kein Testkonto angelegt',
            'Quizabruf fehlgeschlagen; kein Testkonto angelegt',
            'Quiz oder Testzeit ungültig', 'Quiztag oder Prüfsumme ungültig',
            'Quiz enthält nicht genau 50 Fragen', 'Quizfrage ungültig',
            'Quiz-IDs nicht eindeutig', 'Registrierung nicht bestätigt',
            'Abgabe oder Wertung nicht bestätigt', 'Doppelabgabe wurde nicht eindeutig abgelehnt',
            'Ergebnis oder Abgabe-Hash nicht bestätigt', 'Testkontolöschung nicht bestätigt',
            'Kontolöschung nicht abschließend bestätigt',
        }
        reason = str(exc) if type(exc) is ProbeError and str(exc) in safe_messages else 'Netzwerk- oder Prüfungsfehler'
        print('::error title=Ranglisten-Wertungstest::' + reason + '. Keine Ende-zu-Ende-Freigabe.')
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
