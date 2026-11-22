#!/usr/bin/env python3
"""Read-only Cloudflare readiness check. Never print response bodies or credentials."""
import json
import os
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener

MAX_RESPONSE = 1024 * 1024


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def read_json(opener, url, token):
    req = Request(url, headers={'Authorization': 'Bearer ' + token, 'Accept': 'application/json'}, method='GET')
    with opener.open(req, timeout=20) as response:
        if response.status != 200:
            raise ValueError('Unerwarteter HTTP-Status')
        raw = response.read(MAX_RESPONSE + 1)
    if len(raw) > MAX_RESPONSE:
        raise ValueError('Antwort zu groß')
    value = json.loads(raw)
    if not isinstance(value, dict) or value.get('success') is not True:
        raise ValueError('Ungültige API-Antwort')
    return value.get('result')


def diagnose(environ, opener):
    token = environ.get('CLOUDFLARE_API_TOKEN', '').strip()
    account = environ.get('CLOUDFLARE_ACCOUNT_ID', '').strip()
    if not token or not account:
        return False, ['Cloudflare-Zugang im Actions-Lauf nicht vollständig hinterlegt.']
    if not re.fullmatch(r'[0-9a-fA-F]{32}', account):
        return False, ['Cloudflare-Konto-ID im Actions-Lauf hat ein ungültiges Format.']
    base = 'https://api.cloudflare.com/client/v4/accounts/' + account
    messages = []
    ok = True
    for label, path in [('Inhalts-Worker', '/workers/scripts/pflegeexamen-content/settings'),
                        ('D1-Datenbanken', '/d1/database?per_page=10')]:
        try:
            result = read_json(opener, base + path, token)
            if label == 'Inhalts-Worker':
                bindings = result.get('bindings') if isinstance(result, dict) else None
                if not isinstance(bindings, list): raise ValueError('Ungültige Bindings')
                connected = any(isinstance(binding, dict) and binding.get('type') == 'd1' and binding.get('name') == 'DB' for binding in bindings)
                messages.append('Inhalts-Worker: D1-Binding DB ' + ('vorhanden.' if connected else 'nicht gefunden.'))
                if not connected: ok = False
            else:
                if not isinstance(result, list): raise ValueError('Ungültige D1-Liste')
                messages.append('D1-Leseberechtigung: vorhanden; Datenbankinhalte wurden nicht gelesen.')
        except HTTPError as exc:
            ok = False
            messages.append(f'{label}: API-Abfrage fehlgeschlagen (HTTP {exc.code}).')
        except (URLError, OSError, ValueError, TypeError, json.JSONDecodeError):
            ok = False
            messages.append(f'{label}: API-Abfrage oder Antwort nicht verwendbar.')
    messages.append('Dies prüft weder Schreibrechte noch Backup, Migration oder Deployment.')
    return ok, messages


def main():
    ok, messages = diagnose(os.environ, build_opener(NoRedirect()))
    for message in messages:
        print(('::notice::' if ok else '::warning::') + message)
    if not ok:
        print('::error::Cloudflare-Bereitschaft nicht bestätigt; keine Änderungen vorgenommen.')
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
