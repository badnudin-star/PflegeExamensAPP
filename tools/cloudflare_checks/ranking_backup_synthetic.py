#!/usr/bin/env python3
"""Read, encrypt and verify the fixed synthetic D1 only; no production data/artifacts."""
import os
import re
import secrets
import sys
import tempfile
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import build_opener, ProxyHandler
from readiness import NoRedirect
from ranking_provision import CONTENT_ID, PreflightError
from ranking_initialize import DB_ID
from ranking_admin_delete_synthetic import TARGET, NAME
import ranking_backup as backup


def run(env, opener):
    account, token = env.get('CLOUDFLARE_ACCOUNT_ID','').strip(), env.get('CLOUDFLARE_API_TOKEN','').strip()
    if not re.fullmatch(r'[0-9a-fA-F]{32}', account) or not token or TARGET in (DB_ID,CONTENT_ID):
        raise PreflightError('invalid configuration')
    value = backup.capture(opener,'https://api.cloudflare.com/client/v4/accounts/'+account,token,TARGET,NAME)
    if not any(row['id'] == 'fixture-alive' and row['username'] == 'Synthetic-Alive' for row in value['participants']):
        raise PreflightError('test fixture missing')
    password = secrets.token_urlsafe(32)
    blob = backup.encrypt(value,password)
    if b'Synthetic-Alive' in blob or b'fixture-alive' in blob: raise PreflightError('plaintext in ciphertext')
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory)/'fixture.rankbackup'
        backup.write_backup(path,blob,password,TARGET,NAME)
        if backup.decrypt(path.read_bytes(),password,TARGET,NAME) != value: raise PreflightError('readback mismatch')
    print('::notice::Synthetische Test-D1 in einem Snapshot gelesen, verschlüsselt und nach Rücklesen authentifiziert geprüft. Keine Produktionsdaten und kein Artifact verwendet.')
    print('::warning::Dies beweist weder ein echtes Produktionsbackup noch ein vollständiges Löschjournal oder einen sicheren Produktions-Restore.')


def main():
    try: run(os.environ,build_opener(ProxyHandler({}),NoRedirect()))
    except (ValueError,PreflightError,HTTPError,URLError,OSError,KeyError,TypeError,ImportError):
        print('::error::Synthetische Sicherungsprüfung nicht bestätigt; keine Produktionsdaten verändert.')
        return 1
    return 0

if __name__ == '__main__': sys.exit(main())
