#!/usr/bin/env python3
"""Operator-only targeted ranking account deletion, after independent verification.

Never accepts a name as identification. CLI is interactive; identifiers and API
credentials do not go in arguments, logs, artifacts or public Actions inputs.
This tool does not establish ownership and must not be used on uncertain matches.
"""
import getpass
import os
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.request import build_opener, ProxyHandler
from readiness import NoRedirect
from ranking_initialize import DB_ID, OBJECTS, OBJECT_SQL
from ranking_provision import NAME, PreflightError, request
from ranking_journal_initialize import JOURNAL_ID, JOURNAL_NAME
from ranking_journal_backfill import expected_schema

ID = re.compile(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
HASH = re.compile(r'^[0-9a-f]{64}$')
# The production journal identity is fixed in ranking_journal_initialize, never
# taken from interactive input. It must be distinct and checked before writes.


def record_journal(opener, base, token, journal_id, journal_name, target, token_hash):
    if not ID.fullmatch(str(journal_id)) or journal_id == target or not HASH.fullmatch(token_hash):
        raise PreflightError('journal identity invalid')
    info = request(opener, base, token, '/d1/database/' + journal_id).get('result')
    if not isinstance(info, dict) or info.get('uuid') != journal_id or info.get('name') != journal_name:
        raise PreflightError('journal identity mismatch')
    path = '/d1/database/' + journal_id + '/query'
    def rows(sql, params):
        result = request(opener, base, token, path, 'POST', {'sql':sql, 'params':params}).get('result')
        if not isinstance(result, list) or len(result) != 1 or result[0].get('success') is not True:
            raise PreflightError('journal query not confirmed')
        return result[0].get('results')
    schema = rows("SELECT type, name, sql FROM sqlite_master WHERE sql IS NOT NULL AND substr(name,1,7) != 'sqlite_' AND substr(name,1,4) != '_cf_' ORDER BY type,name", [])
    if not isinstance(schema, list) or [(r['type'],r['name'],r['sql']) for r in schema] != expected_schema(False):
        raise PreflightError('journal schema mismatch')
    # D1 computes the time; the hash is pseudonymous and never goes into logs.
    rows("INSERT INTO deletion_journal(token_hash, recorded_at) VALUES (?, CAST(strftime('%s','now') AS INTEGER) * 1000) ON CONFLICT(token_hash) DO NOTHING", [token_hash])
    check = rows('SELECT token_hash, recorded_at FROM deletion_journal WHERE token_hash = ?', [token_hash])
    if (not isinstance(check, list) or len(check) != 1 or check[0].get('token_hash') != token_hash or
            type(check[0].get('recorded_at')) is not int or check[0]['recorded_at'] < 0):
        raise PreflightError('journal readback not confirmed')


def delete_verified(opener, base, token, target, target_name, participant_id, token_hash, journal_id=None, journal_name=None):
    if not ID.fullmatch(participant_id) or not HASH.fullmatch(token_hash):
        raise PreflightError('invalid identifiers')
    info = request(opener, base, token, '/d1/database/' + target).get('result')
    if not isinstance(info, dict) or info.get('uuid') != target or info.get('name') != target_name:
        raise PreflightError('unexpected database')
    path = '/d1/database/' + target + '/query'
    def call(data):
        result = request(opener, base, token, path, 'POST', data).get('result')
        if not isinstance(result,list) or not result or any(not isinstance(r,dict) or r.get('success') is not True for r in result):
            raise PreflightError('invalid query result')
        return result
    def rows(sql, params=None):
        result = call({'sql':sql, 'params':params or []})
        if len(result) != 1 or not isinstance(result[0].get('results'),list): raise PreflightError('invalid rows')
        return result[0]['results']
    objects = rows(OBJECT_SQL)
    expected = OBJECTS | ({('table','quiz_journal_delete_permits'), ('trigger','quiz_participant_requires_journal_permit')} if journal_id is not None else set())
    if {(r['type'],r['name']) for r in objects} != expected: raise PreflightError('schema mismatch')
    if journal_id is not None:
        schema = rows("SELECT type, name, sql FROM sqlite_master WHERE sql IS NOT NULL AND substr(name,1,7) != 'sqlite_' AND substr(name,1,4) != '_cf_' ORDER BY type,name")
        if [(r['type'],r['name'],r['sql']) for r in schema] != expected_schema(True):
            raise PreflightError('production guard schema mismatch')
    if rows('SELECT COUNT(*) AS n FROM quiz_participants WHERE id = ? AND token_hash = ?', [participant_id,token_hash]) != [{'n':1}]:
        raise PreflightError('no unique match')
    if rows('SELECT COUNT(*) AS n FROM quiz_deleted_tokens WHERE token_hash = ?', [token_hash]) != [{'n':0}]:
        raise PreflightError('unexpected existing barrier')
    generation = rows('SELECT generation FROM quiz_snapshot_generation WHERE id = 1')
    if len(generation) != 1 or type(generation[0].get('generation')) is not int or generation[0]['generation'] < 0:
        raise PreflightError('missing generation')
    gen = generation[0]['generation']
    if target == DB_ID and (not JOURNAL_ID or journal_id != JOURNAL_ID or journal_name != JOURNAL_NAME):
        raise PreflightError('production journal not configured')
    if journal_id is not None:
        if not journal_name: raise PreflightError('journal name missing')
        record_journal(opener, base, token, journal_id, journal_name, target, token_hash)
    # One D1 batch: conditional on the same verified id/hash; no user strings
    # in SQL text. Deletion trigger creates the permanent hash barrier.
    exists = 'EXISTS (SELECT 1 FROM quiz_participants WHERE id = ? AND token_hash = ?)'
    statements = []
    if journal_id is not None:
        statements.append({'sql':'INSERT INTO quiz_journal_delete_permits(token_hash) VALUES (?)', 'params':[token_hash]})
    statements.extend([
        {'sql':'DELETE FROM quiz_attempts WHERE participant_id = ? AND ' + exists, 'params':[participant_id,participant_id,token_hash]},
        {'sql':'DELETE FROM quiz_attempt_closures WHERE participant_id = ? AND ' + exists, 'params':[participant_id,participant_id,token_hash]},
        {'sql':'DELETE FROM quiz_participants WHERE id = ? AND token_hash = ?', 'params':[participant_id,token_hash]},
    ])
    if journal_id is not None:
        statements.append({'sql':'DELETE FROM quiz_journal_delete_permits WHERE token_hash = ?', 'params':[token_hash]})
    statements.extend([
        {'sql':'DELETE FROM quiz_hourly_snapshot WHERE EXISTS (SELECT 1 FROM quiz_deleted_tokens WHERE token_hash = ?)', 'params':[token_hash]},
        {'sql':'UPDATE quiz_snapshot_generation SET generation = generation + 1 WHERE id = 1 AND EXISTS (SELECT 1 FROM quiz_deleted_tokens WHERE token_hash = ?)', 'params':[token_hash]},
    ])
    results = call({'batch':statements})
    if len(results) != len(statements): raise PreflightError('incomplete batch response')
    check = rows('''SELECT
      (SELECT COUNT(*) FROM quiz_participants WHERE id = ? OR token_hash = ?) AS accounts,
      (SELECT COUNT(*) FROM quiz_attempts WHERE participant_id = ?) AS attempts,
      (SELECT COUNT(*) FROM quiz_attempt_closures WHERE participant_id = ?) AS closures,
      (SELECT COUNT(*) FROM quiz_deleted_tokens WHERE token_hash = ?) AS barriers,
      (SELECT generation FROM quiz_snapshot_generation WHERE id = 1) AS generation''',
      [participant_id,token_hash,participant_id,participant_id,token_hash])
    if (len(check) != 1 or check[0].get('accounts') != 0 or check[0].get('attempts') != 0 or
        check[0].get('closures') != 0 or check[0].get('barriers') != 1 or
        type(check[0].get('generation')) is not int or check[0]['generation'] <= gen):
        raise PreflightError('deletion not confirmed')


def main():
    try:
        account = getpass.getpass('Cloudflare-Konto-ID (verdeckte Eingabe): ').strip()
        token = getpass.getpass('Cloudflare-API-Token (verdeckte Eingabe): ').strip()
        participant = getpass.getpass('Unabhängig verifizierte Teilnehmer-ID (verdeckte Eingabe): ').strip()
        token_hash = getpass.getpass('Zugehöriger Token-Hash (verdeckte Eingabe): ').strip()
        if not re.fullmatch(r'[0-9a-fA-F]{32}', account) or not token or '\n' in token or '\r' in token:
            raise PreflightError('configuration invalid')
        print('Nur nach unabhängiger Identitätsprüfung: ein Konto samt Wertungen löschen. Löschsperre bleibt bestehen.')
        if input('Zur Bestätigung genau LOESCHEN eingeben: ') != 'LOESCHEN':
            raise PreflightError('confirmation required')
        base = 'https://api.cloudflare.com/client/v4/accounts/' + account
        delete_verified(build_opener(ProxyHandler({}), NoRedirect()), base, token, DB_ID, NAME, participant, token_hash, JOURNAL_ID, JOURNAL_NAME)
        print('Löschung und Löschsperre in der aktiven Ranglisten-Datenbank bestätigt. Backups und externe Kopien bleiben gesondert zu behandeln.')
    except (PreflightError, HTTPError, URLError, OSError, ValueError, KeyError, TypeError, EOFError):
        print('Löschung nicht bestätigt oder Ergebnis unklar. Fall anhand der sicher verwahrten Kennungen prüfen; nicht blind wiederholen.')
        return 1
    return 0

if __name__ == '__main__': sys.exit(main())
