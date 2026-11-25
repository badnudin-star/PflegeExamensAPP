#!/usr/bin/env python3
"""Initialize only the explicitly provisioned, verified empty ranking database."""
import hashlib
import os
from pathlib import Path
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.request import build_opener
from readiness import NoRedirect
from ranking_provision import CONTENT_ID, NAME, PreflightError, request

DB_ID = 'f7dba2f6-722f-4086-af6a-a101dd4da996'
SCHEMA_HASH = '4fd24ea08fc42cd0d76fda1100592b25f4701c386f89caeae9b3063369150fab'
SCHEMA_PATH = Path(__file__).resolve().parents[2] / 'cloudflare-ranking/schema.sql'
OBJECTS = {('index', 'idx_quiz_attempts_participant'),
    *{('table', n) for n in ('quiz_attempt_closures', 'quiz_attempts', 'quiz_deleted_tokens',
                              'quiz_hourly_snapshot', 'quiz_participants', 'quiz_snapshot_generation')},
    *{('trigger', n) for n in ('quiz_attempt_reject_closed', 'quiz_closure_reject_attempt',
                                'quiz_participant_delete_barrier', 'quiz_participant_reject_deleted')}}
OBJECT_SQL = "SELECT type, name FROM sqlite_master WHERE substr(name, 1, 7) != 'sqlite_' AND substr(name, 1, 4) != '_cf_' ORDER BY type, name"


def query(opener, base, token, sql):
    data = request(opener, base, token, '/d1/database/' + DB_ID + '/query', 'POST', {'sql': sql})
    result = data.get('result')
    if not isinstance(result, list) or not result or any(not isinstance(x, dict) or x.get('success') is not True for x in result):
        raise PreflightError('invalid D1 query result')
    return result


def objects(opener, base, token):
    result = query(opener, base, token, OBJECT_SQL)
    if len(result) != 1 or not isinstance(result[0].get('results'), list):
        raise PreflightError('invalid schema inspection')
    rows = result[0]['results']
    if any(not isinstance(row, dict) or not isinstance(row.get('type'), str) or not isinstance(row.get('name'), str) for row in rows):
        raise PreflightError('invalid schema objects')
    return {(r['type'], r['name']) for r in rows}


def run(env, opener):
    token = env.get('CLOUDFLARE_API_TOKEN', '').strip()
    account = env.get('CLOUDFLARE_ACCOUNT_ID', '').strip()
    if not token or not re.fullmatch(r'[0-9a-fA-F]{32}', account) or env.get('RANKING_CONFIRM') != 'true':
        raise PreflightError('missing configuration or confirmation')
    raw = SCHEMA_PATH.read_bytes()
    if hashlib.sha256(raw).hexdigest() != SCHEMA_HASH:
        raise PreflightError('schema hash differs from reviewed version')
    base = 'https://api.cloudflare.com/client/v4/accounts/' + account
    db = request(opener, base, token, '/d1/database/' + DB_ID).get('result')
    if not isinstance(db, dict) or db.get('uuid') != DB_ID or db.get('name') != NAME or DB_ID == CONTENT_ID:
        raise PreflightError('database identity mismatch')
    if objects(opener, base, token):
        raise PreflightError('database is not empty; inspect manually, do not rerun blindly')
    print('Identität der separaten, leeren Ranking-D1 geprüft.')
    # No automatic retry on failure or timeout. A partial schema must be inspected.
    query(opener, base, token, raw.decode('utf-8'))
    if objects(opener, base, token) != OBJECTS:
        raise PreflightError('installed schema differs; inspect manually')
    generation = query(opener, base, token, 'SELECT generation FROM quiz_snapshot_generation WHERE id = 1')
    if len(generation) != 1 or generation[0].get('results') != [{'generation': 0}]:
        raise PreflightError('initial snapshot generation is invalid')
    print('::notice title=Ranking-D1::Schema erfolgreich eingerichtet und kontrolliert.')


def main():
    try:
        run(os.environ, build_opener(NoRedirect()))
    except HTTPError as exc:
        print(f'::error::Cloudflare HTTP {exc.code}. Schema kann teilweise eingerichtet sein; vor Wiederholung manuell prüfen.')
        return 1
    except (ValueError, KeyError, TypeError, OSError, URLError):
        print('::error::Initialisierung fehlgeschlagen oder unklar. Datenbank vor Wiederholung manuell prüfen; kein anderer Dienst wurde geändert.')
        return 1
    return 0

if __name__ == '__main__':
    sys.exit(main())
