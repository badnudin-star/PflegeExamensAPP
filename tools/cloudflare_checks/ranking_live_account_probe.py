#!/usr/bin/env python3
"""Create and delete exactly one synthetic production account, never exposing its token."""
import hashlib
import json
import secrets
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, build_opener
from readiness import NoRedirect
from ranking_live_probe import ORIGIN


class ProbeError(Exception):
    pass


def call(opener, method, path, token, body=None):
    data = json.dumps(body, separators=(',', ':')).encode() if body is not None else None
    req = Request(ORIGIN + path, data=data, method=method, headers={
        'Accept': 'application/json', 'Content-Type': 'application/json',
        'User-Agent': 'python-requests/2.32.3', 'Authorization': 'Bearer ' + token,
    })
    try:
        response = opener.open(req, timeout=30)
    except HTTPError as exc:
        response = exc
    with response:
        raw = response.read(4097)
        if len(raw) > 4096:
            raise ProbeError('Antwort zu groß')
        return response.status, response.headers.get('X-Quiz-Deletion-Receipt'), raw


def run(opener, token, username, pause=time.sleep):
    # Public, random test pseudonym allows identification if cleanup cannot be
    # confirmed. The credential itself is never logged, stored, or returned.
    token_hash = hashlib.sha256(token.encode('ascii')).hexdigest()
    # Record only synthetic identifiers before the request so an interrupted
    # runner can be investigated without storing or revealing the bearer token.
    print(f'::notice title=Synthetisches Testkonto::Name={username}; Token-SHA256={token_hash}', flush=True)
    attempted = False
    cleaned = False
    try:
        attempted = True  # A timed-out request can still have reached the worker.
        status, _, raw = call(opener, 'POST', '/participants', token, {'username': username})
        if status != 201 or json.loads(raw) != {'username': username}:
            raise ProbeError('Registrierung nicht bestätigt')
        status, _, raw = call(opener, 'GET', '/participants/me', token)
        if status != 200 or json.loads(raw) != {'username': username}:
            raise ProbeError('Kontoabfrage fehlgeschlagen')
        status, _, raw = call(opener, 'POST', '/participants', token, {'username': username})
        if status != 200 or json.loads(raw) != {'username': username}:
            raise ProbeError('Wiederholte Registrierung nicht bestätigt')
    finally:
        if attempted:
            for i in range(3):
                try:
                    status, receipt, _ = call(opener, 'DELETE', '/participants/me', token)
                    if status == 204 and receipt == 'v1':
                        cleaned = True
                        break
                except (OSError, URLError, ProbeError):
                    pass
                if i < 2:
                    pause(3)
            if not cleaned:
                # Allows a targeted administrative inspection without recording
                # the bearer credential. Do not blindly delete by name alone.
                print(f'::error::Testkontolöschung nicht bestätigt. Testname={username}; Token-SHA256={token_hash}. Verwaltung muss gezielt prüfen.')
                raise ProbeError('Testkontolöschung nicht bestätigt')
    status, _, _ = call(opener, 'GET', '/participants/me', token)
    if status != 404:
        raise ProbeError('Konto nach bestätigter Löschung weiterhin lesbar oder Status unklar')
    status, receipt, _ = call(opener, 'DELETE', '/participants/me', token)
    if status != 204 or receipt != 'v1':
        raise ProbeError('Wiederholte Löschung nicht bestätigt')
    print('::notice title=Ranglisten-Testkonto::Registrierung, Kontoabfrage, Wiederholung und bestätigte Löschung geprüft.')


def main():
    token = secrets.token_hex(32)
    username = 'Probe-' + secrets.token_hex(6)
    try:
        run(build_opener(NoRedirect()), token, username)
    except (ProbeError, OSError, URLError, ValueError):
        print('::error::Test nicht vollständig erfolgreich; keine vollständige Funktionsfreigabe.')
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
