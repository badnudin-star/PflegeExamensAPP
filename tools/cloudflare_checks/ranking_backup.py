#!/usr/bin/env python3
"""Interactive encrypted ranking-D1 backup/verification, with no plaintext file.

A single SELECT captures data and tombstones in one D1 snapshot. Never logs rows,
credentials or passwords; does not restore or modify the database. A later deletion
is NOT represented, so an older backup must never be restored without a complete,
independently preserved and up-to-date deletion journal and a write-stop protocol.
"""
import datetime as dt
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import sys
from urllib.error import HTTPError, URLError
from urllib.request import ProxyHandler, build_opener
from readiness import NoRedirect
from ranking_initialize import DB_ID, OBJECTS, OBJECT_SQL, SCHEMA_HASH, SCHEMA_PATH
from ranking_provision import NAME, PreflightError, request

MAGIC = b'PFRANK01'
MAX_FILE = 2 * 1024 * 1024
FIELDS = {
    'participants': ('id', 'token_hash', 'username', 'created_at'),
    'attempts': ('participant_id', 'quiz_date', 'correct', 'total', 'submitted_at', 'payload_hash'),
    'closures': ('participant_id', 'quiz_date', 'closed_at'),
    'barriers': ('token_hash', 'deleted_at'),
}
TABLES = {'participants':'quiz_participants', 'attempts':'quiz_attempts',
          'closures':'quiz_attempt_closures', 'barriers':'quiz_deleted_tokens'}
ORDERS = {'participants':'id', 'attempts':'participant_id, quiz_date',
          'closures':'participant_id, quiz_date', 'barriers':'token_hash'}


def snapshot_query():
    # All names are constants; no caller-controlled SQL. Single SELECT ensures
    # data and deletion barriers reflect one SQLite read snapshot.
    parts = []
    for key, fields in FIELDS.items():
        pairs = ', '.join("'" + f + "', " + f for f in fields)
        parts.append(f'(SELECT json_group_array(json_object({pairs})) FROM (SELECT {", ".join(fields)} FROM {TABLES[key]} ORDER BY {ORDERS[key]})) AS {key}')
    parts.append('(SELECT generation FROM quiz_snapshot_generation WHERE id = 1) AS generation')
    return 'SELECT ' + ', '.join(parts)


def strict_json(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result: raise ValueError('duplicate key')
            result[key] = value
        return result
    def bad_constant(_): raise ValueError('invalid constant')
    return json.loads(raw, object_pairs_hook=pairs, parse_constant=bad_constant)


def validate(data, expected_id, expected_name):
    if not isinstance(data,dict) or set(data) != {'format','source_id','source_name','captured_at','generation',*FIELDS}:
        raise ValueError('invalid backup format')
    if data['format'] != 1 or data['source_id'] != expected_id or data['source_name'] != expected_name:
        raise ValueError('wrong backup source')
    try:
        when = dt.datetime.fromisoformat(data['captured_at'])
        if when.utcoffset() != dt.timedelta(0): raise ValueError('timezone')
    except (TypeError,ValueError): raise ValueError('invalid timestamp') from None
    if type(data['generation']) is not int or data['generation'] < 0: raise ValueError('invalid generation')
    raw = SCHEMA_PATH.read_bytes()
    if hashlib.sha256(raw).hexdigest() != SCHEMA_HASH: raise ValueError('schema mismatch')
    db = sqlite3.connect(':memory:')
    try:
        db.execute('PRAGMA foreign_keys=ON')
        db.executescript(raw.decode('utf-8'))
        for key in ('barriers','participants','attempts','closures'):
            values = data[key]
            if not isinstance(values,list) or len(values) > 100000: raise ValueError('invalid collection')
            fields = FIELDS[key]
            for row in values:
                if not isinstance(row,dict) or set(row) != set(fields): raise ValueError('invalid record')
                for field, value in row.items():
                    if field in ('created_at','submitted_at','closed_at','deleted_at','correct','total'):
                        if type(value) is not int or value < 0 or value > 2**63-1: raise ValueError('invalid number')
                    elif field == 'payload_hash' and value is None:
                        continue
                    elif not isinstance(value,str) or not value or len(value) > 1000 or any(ord(c)<32 for c in value):
                        raise ValueError('invalid text')
                    if field in ('token_hash','payload_hash') and value is not None and not re.fullmatch(r'[0-9a-f]{64}', value):
                        raise ValueError('invalid hash')
                    if field == 'quiz_date':
                        try:
                            if dt.date.fromisoformat(value).isoformat() != value: raise ValueError('date')
                        except ValueError: raise ValueError('invalid date') from None
                sql = 'INSERT INTO ' + TABLES[key] + ' (' + ','.join(fields) + ') VALUES (' + ','.join('?' for _ in fields) + ')'
                db.execute(sql, tuple(row[field] for field in fields))
        if db.execute('PRAGMA foreign_key_check').fetchall(): raise ValueError('orphan rows')
    except (sqlite3.Error,OverflowError):
        raise ValueError('inconsistent backup') from None
    finally:
        db.close()
    return data


def capture(opener, base, token, source_id, source_name):
    info = request(opener, base, token, '/d1/database/' + source_id).get('result')
    if not isinstance(info,dict) or info.get('uuid') != source_id or info.get('name') != source_name:
        raise PreflightError('database identity mismatch')
    path = '/d1/database/' + source_id + '/query'
    def rows(sql):
        result = request(opener, base, token, path, 'POST', {'sql':sql,'params':[]}).get('result')
        if not isinstance(result,list) or len(result)!=1 or result[0].get('success') is not True or not isinstance(result[0].get('results'),list):
            raise PreflightError('invalid query')
        return result[0]['results']
    objects = rows(OBJECT_SQL)
    names = {(row['type'],row['name']) for row in objects}
    guarded = OBJECTS | {('table','quiz_journal_delete_permits'), ('trigger','quiz_participant_requires_journal_permit')}
    if names == guarded:
        # Keep existing format-1 backups readable; only accept the exact reviewed
        # guard schema and an empty transient permit table for new captures.
        from ranking_journal_backfill import expected_schema
        schema = rows("SELECT type, name, sql FROM sqlite_master WHERE sql IS NOT NULL AND substr(name,1,7) != 'sqlite_' AND substr(name,1,4) != '_cf_' ORDER BY type,name")
        if [(row['type'],row['name'],row['sql']) for row in schema] != expected_schema(True):
            raise PreflightError('guard schema mismatch')
        if rows('SELECT COUNT(*) AS n FROM quiz_journal_delete_permits') != [{'n':0}]:
            raise PreflightError('outstanding deletion permit')
    elif names != OBJECTS:
        raise PreflightError('schema mismatch')
    result = rows(snapshot_query())
    if len(result)!=1 or not isinstance(result[0],dict) or set(result[0]) != {*FIELDS,'generation'}:
        raise PreflightError('invalid snapshot')
    value = {'format':1,'source_id':source_id,'source_name':source_name,
             'captured_at':dt.datetime.now(dt.timezone.utc).isoformat(), 'generation':result[0]['generation']}
    for key in FIELDS: value[key] = strict_json(result[0][key])
    return validate(value,source_id,source_name)


def validate_password(password):
    if not isinstance(password,str) or len(password)<16 or len(password)>256 or '\n' in password or '\r' in password:
        raise ValueError('invalid password')
    password.encode('utf-8')


def derive(password, salt):
    from cryptography.hazmat.primitives.kdf.scrypt import Scrypt
    validate_password(password)
    return Scrypt(salt=salt, length=32, n=32768, r=8, p=1).derive(password.encode('utf-8'))


def encrypt(data, password):
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    salt, nonce = os.urandom(16), os.urandom(12)
    plain = json.dumps(data,ensure_ascii=False,separators=(',',':')).encode('utf-8')
    if len(plain) > MAX_FILE - 128: raise ValueError('backup too large')
    return MAGIC + salt + nonce + AESGCM(derive(password,salt)).encrypt(nonce,plain,MAGIC+salt)


def decrypt(blob, password, source_id, source_name):
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    from cryptography.exceptions import InvalidTag
    if not isinstance(blob,bytes) or not 52 <= len(blob) <= MAX_FILE or blob[:8] != MAGIC: raise ValueError('invalid file')
    salt, nonce = blob[8:24], blob[24:36]
    try: raw = AESGCM(derive(password,salt)).decrypt(nonce,blob[36:],MAGIC+salt)
    except InvalidTag: raise ValueError('authentication failed') from None
    return validate(strict_json(raw.decode('utf-8')),source_id,source_name)


def safe_path(raw):
    path = Path(raw).expanduser()
    if not path.is_absolute() or path.suffix != '.rankbackup': raise ValueError('absolute path required')
    parent = path.parent.resolve(strict=True)
    repo = SCHEMA_PATH.resolve().parents[1]
    if parent == repo or repo in parent.parents: raise ValueError('backup cannot be in repository')
    return parent / path.name


def write_backup(path, blob, password, source_id, source_name):
    # O_EXCL and O_NOFOLLOW avoid overwriting an existing file or following a
    # final-component symlink. Only ciphertext is ever written to disk.
    fd = os.open(path, os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    created = os.fstat(fd)
    try:
        with os.fdopen(fd,'w+b') as file:
            file.write(blob); file.flush(); os.fsync(file.fileno()); file.seek(0)
            decrypt(file.read(MAX_FILE+1),password,source_id,source_name)
    except BaseException:
        # Do not remove a path that another process replaced in the meantime.
        try:
            current = path.stat(follow_symlinks=False)
            if (current.st_dev,current.st_ino) == (created.st_dev,created.st_ino): path.unlink()
        except OSError: pass
        raise


def main():
    try:
        mode = input('Sicherung erstellen oder prüfen? (erstellen/pruefen): ').strip()
        if mode not in ('erstellen','pruefen'): raise ValueError('invalid mode')
        path = safe_path(input('Absoluter Pfad außerhalb des Repositories (.rankbackup): ').strip())
        password = getpass.getpass('Sicherungs-Passphrase (mindestens 16 Zeichen): ')
        validate_password(password)
        if mode == 'erstellen':
            if password != getpass.getpass('Passphrase wiederholen: '): raise ValueError('password mismatch')
            account = getpass.getpass('Cloudflare-Konto-ID (verdeckte Eingabe): ').strip()
            token = getpass.getpass('Cloudflare-API-Token (verdeckte Eingabe): ').strip()
            if not re.fullmatch(r'[0-9a-fA-F]{32}',account) or not token or '\n' in token or '\r' in token:
                raise ValueError('invalid configuration')
            base = 'https://api.cloudflare.com/client/v4/accounts/' + account
            data = capture(build_opener(ProxyHandler({}),NoRedirect()),base,token,DB_ID,NAME)
            write_backup(path,encrypt(data,password),password,DB_ID,NAME)
            print('Verschlüsselte Ranglisten-Sicherung erstellt und authentifiziert zurückgelesen.')
        else:
            with path.open('rb') as file: blob = file.read(MAX_FILE+1)
            decrypt(blob,password,DB_ID,NAME)
            print('Sicherung entschlüsselt und Struktur/Integrität geprüft; nichts wiederhergestellt.')
        print('Spätere Löschungen sind nicht enthalten. Ein sicherer Produktions-Restore ist damit nicht nachgewiesen.')
    except (ValueError,PreflightError,HTTPError,URLError,OSError,KeyError,TypeError,EOFError,UnicodeError,ImportError):
        print('Sicherung oder Prüfung nicht bestätigt. Keine Datenbankänderung vorgenommen. Zugangsdaten und Inhalte nicht weitergeben.')
        return 1
    return 0

if __name__ == '__main__': sys.exit(main())
