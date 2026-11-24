#!/usr/bin/env python3
"""Reconstruct and verify a narrowly supported D1 backup in a separate empty target.

Only the content_snapshot + quiz_archive schema and one explicitly named archived
quiz day are supported. This is not a general SQL importer. Credentials and
passphrases are read interactively; contents are never printed or persisted outside
private tmpfs by this program. The operator must ensure the target has no Worker
binding before running it. A failed write can leave a partial target: never retry
without inspecting that target. Source and target identity are checked by ID/name.
"""
import argparse
from datetime import date
import getpass
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import tempfile
import uuid
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

SOURCE_NAME = 'pflegeexamen-content'
MAX_ROW = 2_000_000
CHUNK_CHARS = 12_000
SCHEMA_QUERY = 'SELECT type, name, tbl_name, sql FROM sqlite_master'
SNAPSHOT_QUERY = 'SELECT id, revision, payload FROM content_snapshot ORDER BY id'
ARCHIVE_QUERY = 'SELECT date, question_count, payload FROM quiz_archive ORDER BY date'


class RestoreError(Exception):
    """Safe message; never embed data or HTTP errors in it."""


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def strict_json(raw):
    def unique(pairs):
        out = {}
        for key, value in pairs:
            if key in out:
                raise ValueError('duplicate JSON key')
            out[key] = value
        return out
    def invalid(_):
        raise ValueError('non-finite number')
    return json.loads(raw, object_pairs_hook=unique, parse_constant=invalid)


def application_schema(rows):
    return sorted(tuple(row) for row in rows
                  if not row[1].startswith('sqlite_') and row[1] != '_cf_KV')


def authorize(action, a, b, database, source):
    if action in (sqlite3.SQLITE_ATTACH, sqlite3.SQLITE_DETACH):
        return sqlite3.SQLITE_DENY
    if action == sqlite3.SQLITE_FUNCTION and (b or '').lower() == 'load_extension':
        return sqlite3.SQLITE_DENY
    if action == sqlite3.SQLITE_PRAGMA and (a or '').lower() in (
        'temp_store', 'temp_store_directory', 'data_store_directory', 'writable_schema'
    ):
        return sqlite3.SQLITE_DENY
    return sqlite3.SQLITE_OK


def plan_from_sql(sql, expected_revision, expected_day, expected_questions):
    db = sqlite3.connect(':memory:')
    db.execute('PRAGMA temp_store=MEMORY')
    db.enable_load_extension(False)
    db.set_authorizer(authorize)
    try:
        db.executescript(sql)
        if db.execute('PRAGMA integrity_check').fetchall() != [('ok',)]:
            raise RestoreError('Lokale Integritätsprüfung fehlgeschlagen.')
        schema = application_schema(db.execute(SCHEMA_QUERY).fetchall())
        if len(schema) != 2 or {(x[0], x[1], x[2]) for x in schema} != {
            ('table', 'content_snapshot', 'content_snapshot'),
            ('table', 'quiz_archive', 'quiz_archive'),
        } or any(not isinstance(x[3], str) or not x[3] for x in schema):
            raise RestoreError('Sicherung hat ein nicht unterstütztes Schema.')
        for table, expected in (
            ('content_snapshot', ['id', 'revision', 'payload']),
            ('quiz_archive', ['date', 'question_count', 'payload']),
        ):
            columns = [r[1] for r in db.execute('PRAGMA table_info(' + table + ')')]
            if columns != expected:
                raise RestoreError('Sicherung hat nicht unterstützte Spalten.')
        internal = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type = 'table'")}
        for name in ('_cf_KV', 'sqlite_sequence'):
            if name in internal and db.execute('SELECT COUNT(*) FROM "' + name + '"').fetchone()[0]:
                raise RestoreError('Interne Tabelle ist nicht leer; Wiederherstellung abgebrochen.')
        snapshot = db.execute(SNAPSHOT_QUERY).fetchall()
        archive = db.execute(ARCHIVE_QUERY).fetchall()
    finally:
        db.close()

    if (len(snapshot) != 1 or snapshot[0][0] != 1 or type(snapshot[0][1]) is not int
            or snapshot[0][1] != expected_revision or not isinstance(snapshot[0][2], str)):
        raise RestoreError('Gesicherter Snapshot entspricht nicht dem erwarteten Stand.')
    if (len(archive) != 1 or archive[0][0] != expected_day
            or type(archive[0][1]) is not int or archive[0][1] != expected_questions
            or not isinstance(archive[0][2], str)):
        raise RestoreError('Gesichertes Archiv entspricht nicht dem erwarteten Stand.')
    if any(len(row[2].encode('utf-8')) >= MAX_ROW for row in snapshot + archive):
        raise RestoreError('Gesicherter Text erreicht das D1-Zeilenlimit.')

    catalog = strict_json(snapshot[0][2])
    day = strict_json(archive[0][2])
    if (not isinstance(catalog, dict) or catalog.get('revision') != expected_revision
            or not isinstance(catalog.get('quizzes'), list)
            or any(not isinstance(q, dict) for q in catalog['quizzes'])
            or not isinstance(day, dict) or day.get('date') != expected_day
            or day.get('quizzes') != [q for q in catalog['quizzes'] if q.get('published') == expected_day]
            or len(day.get('quizzes', [])) != expected_questions):
        raise RestoreError('Gesicherte Archivkopie stimmt nicht mit dem Snapshot überein.')
    return {'schema': schema, 'snapshot': snapshot, 'archive': archive}


class D1:
    def __init__(self, account, token, opener=None):
        if not re.fullmatch(r'[0-9a-fA-F]{32}', account) or not token or '\n' in token or '\r' in token:
            raise RestoreError('Account ID oder Token ungültig.')
        self.base = f'https://api.cloudflare.com/client/v4/accounts/{account}/d1/database/'
        self.token = token
        # Do not send the account-wide token or private data through an inherited proxy.
        self.opener = opener or build_opener(ProxyHandler({}), NoRedirect())

    def call(self, path, data=None):
        headers = {'Authorization': 'Bearer ' + self.token, 'User-Agent': 'Mozilla/5.0'}
        body = None
        if data is not None:
            headers['Content-Type'] = 'application/json'
            body = json.dumps(data, ensure_ascii=False).encode('utf-8')
        request = Request(self.base + path, headers=headers, data=body)
        with self.opener.open(request, timeout=60) as response:
            value = json.loads(response.read())
        if value.get('success') is not True:
            raise RestoreError('Cloudflare-API meldet einen Fehler.')
        return value['result']

    def info(self, db_id):
        return self.call(db_id)

    def query(self, db_id, sql, params=None):
        result = self.call(db_id + '/query', {'sql': sql, 'params': params or []})
        if not result or result[0].get('success') is not True:
            raise RestoreError('D1-Abfrage fehlgeschlagen.')
        return result[0]


def rows(result, columns):
    return [tuple(row[col] for col in columns) for row in result.get('results', [])]


def verify_identity(d1, source_id, target_id, target_name):
    if (str(uuid.UUID(source_id)) != source_id or str(uuid.UUID(target_id)) != target_id
            or source_id == target_id or target_name == SOURCE_NAME
            or 'restore' not in target_name.lower()):
        raise RestoreError('Quell- und Zielangaben sind nicht sicher unterscheidbar.')
    source, target = d1.info(source_id), d1.info(target_id)
    if (source.get('uuid') != source_id or source.get('name') != SOURCE_NAME
            or target.get('uuid') != target_id or target.get('name') != target_name):
        raise RestoreError('Quell- oder Zieldatenbank nicht eindeutig bestätigt.')


def destination_schema(d1, target_id):
    result = d1.query(target_id, SCHEMA_QUERY)
    return application_schema(rows(result, ('type', 'name', 'tbl_name', 'sql')))


def require_empty(d1, target_id):
    if destination_schema(d1, target_id):
        raise RestoreError('Restore-Ziel ist nicht leer; kein Schreiben gestartet.')


def reconstruct(d1, target_id, plan, progress=lambda *_: None):
    # Recheck just before first write. The target must not be bound to a Worker.
    require_empty(d1, target_id)
    for _, _, _, sql in plan['schema']:
        d1.query(target_id, sql)

    ident, revision, payload = plan['snapshot'][0]
    d1.query(target_id, 'INSERT INTO content_snapshot (id, revision, payload) VALUES (?, ?, ?)',
             [ident, revision, ''])
    day, count, archived_payload = plan['archive'][0]
    d1.query(target_id, 'INSERT INTO quiz_archive (date, question_count, payload) VALUES (?, ?, ?)',
             [day, count, ''])

    for table, key_column, key, text in (
        ('content_snapshot', 'id', ident, payload),
        ('quiz_archive', 'date', day, archived_payload),
    ):
        total = (len(text) + CHUNK_CHARS - 1) // CHUNK_CHARS
        for index, pos in enumerate(range(0, len(text), CHUNK_CHARS), 1):
            result = d1.query(target_id,
                f'UPDATE {table} SET payload = payload || ? WHERE {key_column} = ?',
                [text[pos:pos + CHUNK_CHARS], key])
            if result.get('meta', {}).get('changes') != 1:
                raise RestoreError('Schreibprüfung fehlgeschlagen; Ziel könnte unvollständig sein.')
            if index % 20 == 0 or index == total:
                progress(table, index, total)

    schema = destination_schema(d1, target_id)
    snapshot = rows(d1.query(target_id, SNAPSHOT_QUERY), ('id', 'revision', 'payload'))
    archive = rows(d1.query(target_id, ARCHIVE_QUERY), ('date', 'question_count', 'payload'))
    if schema != plan['schema'] or snapshot != plan['snapshot'] or archive != plan['archive']:
        raise RestoreError('Zielvergleich fehlgeschlagen; Ziel könnte unvollständig sein.')


def args_for_run():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backup', type=Path, required=True)
    parser.add_argument('--source-id', required=True)
    parser.add_argument('--target-id', required=True)
    parser.add_argument('--target-name', required=True)
    parser.add_argument('--expected-revision', type=int, required=True)
    parser.add_argument('--expected-day', required=True)
    parser.add_argument('--expected-questions', type=int, required=True)
    parser.add_argument('--confirm-no-worker-binding', action='store_true', required=True)
    return parser.parse_args()


def main():
    args = args_for_run()
    os.umask(0o077)
    writing = False
    stage = 'Vorprüfung'
    try:
        if (date.fromisoformat(args.expected_day).isoformat() != args.expected_day
                or args.expected_revision < 1 or args.expected_questions < 1):
            raise RestoreError('Erwarteter Sicherungsstand ist ungültig.')
        typ = subprocess.check_output(['findmnt', '-no', 'FSTYPE', '-T', '/dev/shm'], text=True).strip()
        swap = subprocess.check_output(['swapon', '--noheadings'], text=True).strip()
        if typ != 'tmpfs' or swap or not args.backup.is_file():
            raise RestoreError('Temporärspeicher, Swap oder Sicherungsdatei prüfen.')
        account = getpass.getpass('Cloudflare Account ID eingeben: ').strip()
        token = getpass.getpass('Temporären D1-Token eingeben: ').strip()
        passphrase = getpass.getpass('Sicherungs-Passphrase eingeben: ')
        if not passphrase:
            raise RestoreError('Sicherungs-Passphrase fehlt.')
        d1 = D1(account, token)
        stage = 'Quell- und Zielprüfung'
        verify_identity(d1, args.source_id, args.target_id, args.target_name)
        require_empty(d1, args.target_id)

        with tempfile.TemporaryDirectory(prefix='pflege-restore-', dir='/dev/shm') as folder:
            tmp = Path(folder)
            gnupg = tmp / 'gnupg'
            gnupg.mkdir(mode=0o700)
            # Avoid inherited GPG options, agent sockets, library injection and
            # temporary-directory overrides. Resolve standard tools from system paths.
            env = {'PATH': '/usr/bin:/bin', 'HOME': str(tmp), 'GNUPGHOME': str(gnupg),
                   'TMPDIR': str(tmp), 'LANG': 'C.UTF-8'}
            try:
                stage = 'Entschlüsselung und lokale Prüfung'
                sql = tmp / 'backup.sql'
                with (tmp / 'gpg.log').open('wb') as log:
                    subprocess.run(
                        ['gpg', '--batch', '--yes', '--quiet', '--no-symkey-cache',
                         '--pinentry-mode', 'loopback', '--passphrase-fd', '0',
                         '--output', str(sql), '--decrypt', str(args.backup)],
                        input=(passphrase + '\n').encode(), stdout=subprocess.DEVNULL,
                        stderr=log, env=env, check=True,
                    )
                del passphrase
                plan = plan_from_sql(sql.read_text(encoding='utf-8'), args.expected_revision,
                                     args.expected_day, args.expected_questions)
                print(f'Geprüftes Ziel: {args.target_name} ({args.target_id})')
                print(f'Sicherung: Revision {args.expected_revision}; Archivtag {args.expected_day}; '
                      f'{args.expected_questions} Fragen.')
                print('Das Ziel muss leer und darf nicht an einen Worker gebunden sein.')
                confirmation = input('Zum Start die Ziel-Datenbank-ID erneut eingeben: ').strip()
                if confirmation != args.target_id:
                    raise RestoreError('Zielbestätigung stimmt nicht; kein Schreiben gestartet.')
                # Repeat account/name checks immediately before the first write.
                verify_identity(d1, args.source_id, args.target_id, args.target_name)
                stage = 'Wiederherstellung und Vergleich'
                writing = True
                reconstruct(d1, args.target_id, plan,
                    progress=lambda table, number, total: print(
                        f'{table}: {number} von {total} Teilen übertragen.', flush=True))
                print('Wiederherstellung und vollständiger Vergleich erfolgreich.')
                print('Schema, Snapshot und Archiv stimmen mit der Sicherung überein.')
            finally:
                subprocess.run(['gpgconf', '--kill', 'gpg-agent'], env=env,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    except RestoreError as exc:
        print(stage + ': ' + str(exc))
        if writing:
            print('Restore-Ziel könnte teilweise beschrieben sein. Nicht erneut starten.')
        return 1
    except Exception:
        print(stage + ': fehlgeschlagen. Keine Details oder Inhalte ausgegeben.')
        if writing:
            print('Restore-Ziel könnte teilweise beschrieben sein. Nicht erneut starten.')
        else:
            print('Kein Schreiben gestartet.')
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
