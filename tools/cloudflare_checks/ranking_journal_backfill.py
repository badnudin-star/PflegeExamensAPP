#!/usr/bin/env python3
"""Backfill production deletion barriers into the separate journal, never log rows.

Run only after the reviewed guard migration and before allowing an unreviewed
restore. A successful copy is not a durable off-account backup or proof against
privileged SQL changes. Both DB identities and guard/trigger definitions are
verified before any write. Journal inserts are idempotent; no deletion occurs.
"""
import os
from pathlib import Path
import re
import sqlite3
import sys
from urllib.error import HTTPError, URLError
from urllib.request import build_opener, ProxyHandler
from readiness import NoRedirect
from ranking_initialize import DB_ID, SCHEMA_PATH, OBJECT_SQL
from ranking_journal_initialize import JOURNAL_ID, JOURNAL_NAME, SCHEMA as JOURNAL_SCHEMA
from ranking_provision import NAME, CONTENT_ID, PreflightError, request

ROOT = Path(__file__).resolve().parents[2]
GUARD = ROOT / 'cloudflare-ranking/migrations/0004_journal_delete_guard.sql'
MAX_ROWS = 100000


def expected_schema(primary):
    db = sqlite3.connect(':memory:')
    try:
        db.executescript(SCHEMA_PATH.read_text() + '\n' + GUARD.read_text() if primary else JOURNAL_SCHEMA.read_text())
        return db.execute("SELECT type, name, sql FROM sqlite_master WHERE sql IS NOT NULL AND substr(name,1,7) != 'sqlite_' ORDER BY type,name").fetchall()
    finally: db.close()


def run(env, opener):
    account = env.get('CLOUDFLARE_ACCOUNT_ID', '').strip()
    token = env.get('CLOUDFLARE_API_TOKEN', '').strip()
    if (env.get('GITHUB_REF') != 'refs/heads/main' or env.get('GITHUB_EVENT_NAME') != 'workflow_dispatch'
        or env.get('CONFIRM_JOURNAL') != 'true' or not re.fullmatch(r'[0-9a-fA-F]{32}', account)
        or not token or '\n' in token or '\r' in token or JOURNAL_ID in (DB_ID, CONTENT_ID)):
        raise PreflightError('invalid configuration')
    base = 'https://api.cloudflare.com/client/v4/accounts/' + account
    for ident, name in ((DB_ID,NAME),(JOURNAL_ID,JOURNAL_NAME)):
        info = request(opener,base,token,'/d1/database/' + ident).get('result')
        if not isinstance(info,dict) or info.get('uuid') != ident or info.get('name') != name:
            raise PreflightError('database identity mismatch')
    def rows(ident, sql, params=None):
        result = request(opener,base,token,'/d1/database/' + ident + '/query','POST',{'sql':sql,'params':params or []}).get('result')
        if (not isinstance(result,list) or len(result) != 1 or not isinstance(result[0],dict) or
                result[0].get('success') is not True or not isinstance(result[0].get('results'),list)):
            raise PreflightError('query not confirmed')
        return result[0]['results']
    def verify_schema(ident, primary):
        result = rows(ident, "SELECT type, name, sql FROM sqlite_master WHERE sql IS NOT NULL AND substr(name,1,7) != 'sqlite_' AND substr(name,1,4) != '_cf_' ORDER BY type,name")
        try: actual = [(r['type'],r['name'],r['sql']) for r in result]
        except (TypeError,KeyError): raise PreflightError('invalid schema') from None
        if actual != expected_schema(primary): raise PreflightError('unexpected schema or guard')
    def verify_no_permits():
        if rows(DB_ID, 'SELECT COUNT(*) AS n FROM quiz_journal_delete_permits') != [{'n':0}]:
            raise PreflightError('unexpected outstanding permits')
    verify_schema(DB_ID,True); verify_schema(JOURNAL_ID,False); verify_no_permits()
    last, count = '', 0
    while True:
        batch = rows(DB_ID, 'SELECT token_hash, deleted_at FROM quiz_deleted_tokens WHERE token_hash > ? ORDER BY token_hash LIMIT 100', [last])
        if len(batch) > 100: raise PreflightError('invalid page')
        if not batch: break
        for row in batch:
            if not isinstance(row,dict) or set(row) != {'token_hash','deleted_at'}: raise PreflightError('invalid row')
            h, ts = row['token_hash'], row['deleted_at']
            if not isinstance(h,str) or not re.fullmatch(r'[0-9a-f]{64}',h) or h <= last or type(ts) is not int or not 0 <= ts < 2**53:
                raise PreflightError('invalid barrier')
            count += 1
            if count > MAX_ROWS: raise PreflightError('journal exceeds safety limit')
            rows(JOURNAL_ID, 'INSERT INTO deletion_journal(token_hash, recorded_at) VALUES (?, ?) ON CONFLICT(token_hash) DO NOTHING', [h,ts])
            confirmed = rows(JOURNAL_ID, 'SELECT token_hash, recorded_at FROM deletion_journal WHERE token_hash = ?', [h])
            if (len(confirmed) != 1 or confirmed[0].get('token_hash') != h or
                type(confirmed[0].get('recorded_at')) is not int or confirmed[0]['recorded_at'] < 0):
                raise PreflightError('journal readback failed')
            last = h
    verify_schema(DB_ID,True); verify_schema(JOURNAL_ID,False); verify_no_permits()
    print('::notice::Vorhandene Löschsperren ins separate Journal kopiert und einzeln rückgelesen; keine Hashes protokolliert.')
    print('::warning::Noch kein produktiver Restore: Betrieb, Vollständigkeit bei allen Schreibwegen und unabhängige Sicherung separat prüfen.')


def main():
    try: run(os.environ,build_opener(ProxyHandler({}),NoRedirect()))
    except HTTPError as exc:
        print(f'::error::Cloudflare HTTP {exc.code}; Journalabgleich nicht bestätigt. Bestand prüfen.')
        return 1
    except (PreflightError, ValueError, KeyError, TypeError, OSError, URLError, sqlite3.Error):
        print('::error::Journalabgleich nicht bestätigt. Kein Restore zulässig; Zustand prüfen.')
        return 1
    return 0

if __name__ == '__main__': sys.exit(main())
