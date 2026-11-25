#!/usr/bin/env python3
"""Guarded provisioning of a NEW, dedicated ranking D1. Never modifies an existing DB.

Only fixed Cloudflare endpoints and names are used. An ambiguous create must be
investigated manually; the script never automatically reuses an existing name.
"""
import json
import os
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.request import Request, build_opener
from readiness import NoRedirect

NAME = 'pflegeexamen-ranking-prod'
WORKER = 'pflege-examen-ranking'
CONTENT_ID = '80458a91-b0b0-49a9-8937-cccc7f6d9be3'
UUID = re.compile(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
MAX_BYTES = 1024 * 1024


class PreflightError(ValueError):
    pass


def request(opener, base, token, path, method='GET', data=None):
    body = None if data is None else json.dumps(data).encode()
    req = Request(base + path, data=body, method=method, headers={
        'Authorization': 'Bearer ' + token, 'Accept': 'application/json', 'Content-Type': 'application/json'})
    with opener.open(req, timeout=30) as response:
        raw = response.read(MAX_BYTES + 1)
        if len(raw) > MAX_BYTES:
            raise PreflightError('response too large')
        result = json.loads(raw)
    if not isinstance(result, dict) or result.get('success') is not True:
        raise PreflightError('invalid API response')
    return result


def run(env, opener):
    token = env.get('CLOUDFLARE_API_TOKEN', '').strip()
    account = env.get('CLOUDFLARE_ACCOUNT_ID', '').strip()
    mode = env.get('RANKING_PROVISION_MODE', '')
    confirmed = env.get('RANKING_CONFIRM', '') == 'true'
    if not token or not re.fullmatch(r'[0-9a-fA-F]{32}', account) or mode not in ('inspect', 'create'):
        raise PreflightError('invalid configuration')
    if mode == 'create' and not confirmed:
        raise PreflightError('confirmation required')
    base = 'https://api.cloudflare.com/client/v4/accounts/' + account
    content = request(opener, base, token, '/workers/scripts/pflegeexamen-content/settings')['result']
    bindings = content.get('bindings') if isinstance(content, dict) else None
    if not isinstance(bindings, list) or not any(isinstance(b, dict) and b.get('name') == 'DB' and
        b.get('type') == 'd1' and b.get('id', b.get('database_id')) == CONTENT_ID for b in bindings):
        raise PreflightError('content database binding not confirmed')
    print('Inhalts-Worker und erwartete Inhalts-D1 erkannt; keine Änderung daran.')
    found = []
    for page in range(1, 101):
        result = request(opener, base, token, f'/d1/database?per_page=100&page={page}')
        values = result.get('result')
        info = result.get('result_info')
        if not isinstance(values, list) or not isinstance(info, dict):
            raise PreflightError('invalid D1 list')
        found.extend(x for x in values if isinstance(x, dict) and x.get('name') == NAME)
        count, current, per_page, total = (info.get(k) for k in ('count', 'page', 'per_page', 'total_count'))
        if (any(type(v) is not int for v in (count, current, per_page, total)) or
            current != page or count != len(values) or per_page < 1 or total < 0 or total > 10000 or count > per_page):
            raise PreflightError('invalid D1 pagination')
        if page * per_page >= total: break
        if count != per_page:
            raise PreflightError('incomplete D1 list')
    else:
        raise PreflightError('too many D1 pages')
    if found:
        raise PreflightError('ranking database name already exists; never reuse automatically')
    try:
        request(opener, base, token, f'/workers/scripts/{WORKER}/settings')
    except HTTPError as exc:
        if exc.code != 404: raise
    else:
        raise PreflightError('ranking worker name already exists; never overwrite automatically')
    subdomain = request(opener, base, token, '/workers/subdomain').get('result')
    if not isinstance(subdomain, dict) or subdomain.get('subdomain') != 'badnudin':
        raise PreflightError('unexpected workers.dev subdomain')
    print('Gewünschte Ranking-Datenbank und Worker-Name sind frei; erwartete workers.dev-Subdomain erkannt.')
    if mode == 'inspect':
        print('Nur geprüft; keine Datenbank erstellt und kein Worker bereitgestellt.')
        return
    # A lost response is ambiguous: do not retry or adopt a same-named DB.
    created = request(opener, base, token, '/d1/database', 'POST', {'name':NAME}).get('result')
    if not isinstance(created, dict) or created.get('name') != NAME or not UUID.fullmatch(str(created.get('uuid', ''))):
        raise PreflightError('creation response ambiguous; inspect manually before retry')
    print('Neue separate Ranking-D1 erstellt: ' + created['uuid'])
    print('Schema und Worker wurden noch nicht eingerichtet; die Datenbank ist zunächst leer.')


def main():
    try:
        run(os.environ, build_opener(NoRedirect()))
    except HTTPError as exc:
        print(f'::error::Cloudflare-Anfrage fehlgeschlagen (HTTP {exc.code}); bei POST zunächst manuell prüfen, ob eine Datenbank erstellt wurde.')
        return 1
    except PreflightError as exc:
        print(f'::error::Prüfung abgebrochen: {exc}. Keine vorhandene Ressource automatisch verwenden oder überschreiben.')
        return 1
    except (ValueError, KeyError, TypeError, json.JSONDecodeError, URLError, OSError):
        print('::error::Prüfung fehlgeschlagen oder Ergebnis unklar; keine vorhandene Ressource automatisch verwenden oder überschreiben.')
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
