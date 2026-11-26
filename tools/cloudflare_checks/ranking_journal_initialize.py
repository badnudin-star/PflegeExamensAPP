#!/usr/bin/env python3
"""Initialize only the known, separate, empty deletion-journal D1."""
import hashlib
import os
from pathlib import Path
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.request import build_opener, ProxyHandler
from readiness import NoRedirect
from ranking_initialize import DB_ID, OBJECT_SQL
from ranking_provision import CONTENT_ID, PreflightError, request
from ranking_journal_provision import JOURNAL_NAME

JOURNAL_ID = '64c6def3-9316-4128-b5f8-a6a4e7a4dae8'
SCHEMA = Path(__file__).resolve().parents[2] / 'cloudflare-ranking/journal-schema.sql'
SCHEMA_HASH = 'ac19225e3916c58764ba7514106e90927a7a118bf63bf9aadde88723cf345b83'
OBJECTS = {('table','deletion_journal'), *{('trigger', name) for name in
    ('deletion_journal_no_replace','deletion_journal_no_update','deletion_journal_no_delete')}}


def run(env, opener):
    account = env.get('CLOUDFLARE_ACCOUNT_ID', '').strip()
    token = env.get('CLOUDFLARE_API_TOKEN', '').strip()
    if (env.get('GITHUB_REF') != 'refs/heads/main' or env.get('GITHUB_EVENT_NAME') != 'workflow_dispatch'
        or env.get('CONFIRM_JOURNAL') != 'true' or not re.fullmatch(r'[0-9a-fA-F]{32}', account)
        or not token or '\n' in token or '\r' in token or JOURNAL_ID in (DB_ID, CONTENT_ID)):
        raise PreflightError('invalid configuration')
    raw = SCHEMA.read_bytes()
    if hashlib.sha256(raw).hexdigest() != SCHEMA_HASH: raise PreflightError('unexpected schema')
    base = 'https://api.cloudflare.com/client/v4/accounts/' + account
    info = request(opener, base, token, '/d1/database/' + JOURNAL_ID).get('result')
    if not isinstance(info, dict) or info.get('uuid') != JOURNAL_ID or info.get('name') != JOURNAL_NAME:
        raise PreflightError('journal identity mismatch')
    path = '/d1/database/' + JOURNAL_ID + '/query'
    def query(sql):
        result = request(opener, base, token, path, 'POST', {'sql':sql}).get('result')
        if not isinstance(result, list) or not result or any(not isinstance(r,dict) or r.get('success') is not True for r in result):
            raise PreflightError('invalid query response')
        return result
    def objects():
        result = query(OBJECT_SQL)
        if len(result) != 1 or not isinstance(result[0].get('results'), list): raise PreflightError('invalid objects')
        return {(r['type'], r['name']) for r in result[0]['results']}
    if objects(): raise PreflightError('journal is not empty')
    # Never retry after ambiguity: inspect its actual state first.
    query(raw.decode('utf-8'))
    if objects() != OBJECTS: raise PreflightError('journal schema incomplete')
    result = query('SELECT COUNT(*) AS n FROM deletion_journal')
    if len(result) != 1 or result[0].get('results') != [{'n':0}]: raise PreflightError('journal not empty')
    print('::notice::Separates leeres Löschjournal initialisiert; noch nicht mit Löschungen verbunden oder vollständig.')


def main():
    try:
        run(os.environ, build_opener(ProxyHandler({}), NoRedirect()))
    except HTTPError as exc:
        print(f'::error::Cloudflare HTTP {exc.code}; Zustand prüfen, vor Wiederholung nicht blind erneut ausführen.')
        return 1
    except (PreflightError, ValueError, KeyError, TypeError, OSError, URLError):
        print('::error::Journal-Initialisierung nicht bestätigt; Zustand prüfen, nicht blind wiederholen.')
        return 1
    return 0

if __name__ == '__main__': sys.exit(main())
