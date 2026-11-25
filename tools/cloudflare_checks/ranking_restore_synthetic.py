#!/usr/bin/env python3
"""Test a synthetic old snapshot plus later deletion barriers in a newly created D1.

Never reads, changes or exports production records. The new test database remains
for manual inspection/cleanup; never retry blindly after an ambiguous create.
This fixture is not a production backup or a general restore tool.
"""
import hashlib
import os
import re
import secrets
import sqlite3
import sys
from urllib.error import HTTPError, URLError
from urllib.request import build_opener, ProxyHandler
from readiness import NoRedirect
from ranking_provision import CONTENT_ID, PreflightError, UUID, request
from ranking_initialize import DB_ID, OBJECTS, OBJECT_SQL, SCHEMA_HASH, SCHEMA_PATH


def make_fixture(schema):
    current = sqlite3.connect(':memory:')
    old = sqlite3.connect(':memory:')
    try:
        current.executescript(schema)
        participants = [('fixture-alive', 'a'*64, 'Synthetic-Alive', 1000),
                        ('fixture-deleted', 'b'*64, 'Synthetic-Deleted', 2000)]
        current.executemany('INSERT INTO quiz_participants VALUES (?, ?, ?, ?)', participants)
        current.executemany('INSERT INTO quiz_attempts VALUES (?, ?, ?, ?, ?, ?)', [
            ('fixture-alive','2026-01-01', 10, 50, 3000, 'c'*64),
            ('fixture-deleted','2026-01-01', 20, 50, 4000, 'd'*64)])
        current.execute("INSERT INTO quiz_attempt_closures VALUES ('fixture-alive','2026-01-02',5000)")
        current.commit()
        current.backup(old)  # snapshot taken before the later deletion
        current.execute("DELETE FROM quiz_attempts WHERE participant_id='fixture-deleted'")
        current.execute("DELETE FROM quiz_participants WHERE id='fixture-deleted'")
        current.commit()
        return {'participants':old.execute('SELECT * FROM quiz_participants ORDER BY id').fetchall(),
                'attempts':old.execute('SELECT * FROM quiz_attempts ORDER BY participant_id').fetchall(),
                'closures':old.execute('SELECT * FROM quiz_attempt_closures').fetchall(),
                'barriers':current.execute('SELECT * FROM quiz_deleted_tokens').fetchall()}
    finally:
        old.close(); current.close()


def run(env, opener):
    token, account = env.get('CLOUDFLARE_API_TOKEN','').strip(), env.get('CLOUDFLARE_ACCOUNT_ID','').strip()
    if not token or not re.fullmatch(r'[0-9a-fA-F]{32}', account) or env.get('RESTORE_CONFIRM') != 'true':
        raise PreflightError('missing confirmation or configuration')
    raw = SCHEMA_PATH.read_bytes()
    if hashlib.sha256(raw).hexdigest() != SCHEMA_HASH: raise PreflightError('unexpected schema')
    fixture = make_fixture(raw.decode('utf-8'))
    base = 'https://api.cloudflare.com/client/v4/accounts/' + account
    name = 'pflege-ranking-restore-synthetic-' + secrets.token_hex(8)
    print('::notice::Neuer synthetischer D1-Testname: ' + name, flush=True)
    # Only a randomly named fresh D1, never supplied by caller or reused. If this
    # response is lost the caller must inspect the printed name, not retry blindly.
    created = request(opener, base, token, '/d1/database', 'POST', {'name':name}).get('result')
    if not isinstance(created, dict) or created.get('name') != name or not UUID.fullmatch(str(created.get('uuid',''))):
        raise PreflightError('uncertain create result')
    target = created['uuid']
    if target in (DB_ID, CONTENT_ID): raise PreflightError('target collides with production')
    print('::notice::Neue Test-D1-ID: ' + target + '. Nur synthetische Daten; für gezielte manuelle Bereinigung vormerken.', flush=True)
    info = request(opener, base, token, '/d1/database/' + target).get('result')
    if not isinstance(info, dict) or info.get('uuid') != target or info.get('name') != name:
        raise PreflightError('target identity mismatch')
    def query(sql, params=None):
        result = request(opener, base, token, '/d1/database/' + target + '/query', 'POST',
                         {'sql':sql, 'params':params or []}).get('result')
        if not isinstance(result,list) or not result or any(not isinstance(r,dict) or r.get('success') is not True for r in result):
            raise PreflightError('invalid query result')
        return result
    def rows(sql):
        result = query(sql)
        if len(result) != 1 or not isinstance(result[0].get('results'), list): raise PreflightError('invalid rows')
        return result[0]['results']
    if rows(OBJECT_SQL): raise PreflightError('new target not empty')
    query(raw.decode('utf-8'))
    if {(r['type'],r['name']) for r in rows(OBJECT_SQL)} != OBJECTS: raise PreflightError('schema mismatch')
    # Install the complete later tombstone set first, then omit matching accounts
    # from the older snapshot. Never import a stale public snapshot.
    for row in fixture['barriers']:
        query('INSERT INTO quiz_deleted_tokens(token_hash, deleted_at) VALUES (?, ?)', list(row))
    for row in fixture['participants']:
        query('INSERT INTO quiz_participants SELECT ?, ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM quiz_deleted_tokens WHERE token_hash = ?)', list(row) + [row[1]])
    for row in fixture['attempts']:
        query('INSERT INTO quiz_attempts SELECT ?, ?, ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM quiz_participants WHERE id = ?)', list(row) + [row[0]])
    for row in fixture['closures']:
        query('INSERT INTO quiz_attempt_closures SELECT ?, ?, ? WHERE EXISTS (SELECT 1 FROM quiz_participants WHERE id = ?)', list(row) + [row[0]])
    counts = rows('''SELECT (SELECT COUNT(*) FROM quiz_participants) AS participants,
      (SELECT COUNT(*) FROM quiz_attempts) AS attempts, (SELECT COUNT(*) FROM quiz_attempt_closures) AS closures,
      (SELECT COUNT(*) FROM quiz_deleted_tokens) AS barriers, (SELECT COUNT(*) FROM quiz_hourly_snapshot) AS snapshots,
      (SELECT COUNT(*) FROM quiz_participants WHERE id = 'fixture-alive' AND token_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa') AS alive,
      (SELECT COUNT(*) FROM quiz_participants WHERE id = 'fixture-deleted') AS resurrected,
      (SELECT COUNT(*) FROM quiz_attempts WHERE participant_id = 'fixture-deleted') AS deleted_attempts,
      (SELECT COUNT(*) FROM quiz_attempts WHERE participant_id = 'fixture-alive' AND correct = 10 AND total = 50) AS correct_attempt''')
    if counts != [{'participants':1,'attempts':1,'closures':1,'barriers':1,'snapshots':0,
                   'alive':1,'resurrected':0,'deleted_attempts':0,'correct_attempt':1}]:
        raise PreflightError('restore verification failed')
    print('::notice title=Synthetischer D1-Restore::Alter Snapshot und spätere Löschsperre abgeglichen: aktives Konto und Wertung erhalten, gelöschtes Konto nicht wiederhergestellt; kein alter öffentlicher Snapshot importiert.', flush=True)
    print('::warning::Kein produktives Backup oder vollständiges Wiederherstellungsverfahren nachgewiesen. Test-D1 bleibt zur kontrollierten Bereinigung bestehen.')


def main():
    try:
        run(os.environ, build_opener(ProxyHandler({}), NoRedirect()))
    except HTTPError as exc:
        print(f'::error::D1-Test nicht abgeschlossen (HTTP {exc.code}). Testname und ggf. ID prüfen; keine Wiederholung ohne Bestandsprüfung.')
        return 1
    except (PreflightError, OSError, URLError, ValueError, KeyError, TypeError, sqlite3.Error):
        print('::error::D1-Test nicht abgeschlossen oder Ergebnis unklar. Testname und ggf. ID prüfen; keine Wiederholung ohne Bestandsprüfung.')
        return 1
    return 0

if __name__ == '__main__': sys.exit(main())
