#!/usr/bin/env python3
"""Opt-in read-only live backup-path probe; never retains production data or keys.

The production data exists briefly in the runner's process memory. Only encrypted
bytes reach a temporary file; it and the ephemeral key are discarded. No Actions
artifact is produced. This is NOT a backup and cannot restore deleted records.
"""
import os
import re
import secrets
import tempfile
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import build_opener, ProxyHandler
from readiness import NoRedirect
from ranking_initialize import DB_ID
from ranking_provision import NAME, PreflightError
import ranking_backup as backup


def run(env, opener):
    account, token = env.get('CLOUDFLARE_ACCOUNT_ID','').strip(), env.get('CLOUDFLARE_API_TOKEN','').strip()
    temp_dir = env.get('RUNNER_TEMP','')
    if (env.get('LIVE_BACKUP_PROBE_CONFIRM') != 'true' or not token or
        not re.fullmatch(r'[0-9a-fA-F]{32}',account) or not temp_dir or not Path(temp_dir).is_dir()):
        raise PreflightError('invalid configuration')
    data = backup.capture(opener,'https://api.cloudflare.com/client/v4/accounts/'+account,token,DB_ID,NAME)
    password = secrets.token_urlsafe(32)
    blob = backup.encrypt(data,password)
    with tempfile.TemporaryDirectory(prefix='ranking-backup-check-', dir=temp_dir) as directory:
        path = Path(directory)/'test.rankbackup'
        backup.write_backup(path,blob,password,DB_ID,NAME)
        if backup.decrypt(path.read_bytes(),password,DB_ID,NAME) != data:
            raise PreflightError('readback mismatch')
    print('::notice::Produktive Ranglisten-D1 lesend geprüft; temporärer Chiffretext mit Einmalschlüssel authentifiziert zurückgelesen und entfernt.')
    print('::warning::Kein Backup und kein Schlüssel gespeichert; Produktions-Restore und vollständiges Löschjournal bleiben unbewiesen.')


def main():
    try: run(os.environ,build_opener(ProxyHandler({}),NoRedirect()))
    except (ValueError,PreflightError,HTTPError,URLError,OSError,KeyError,TypeError,ImportError):
        print('::error::Produktiver Lese- und Verschlüsselungstest nicht bestätigt. Keine Datenbankänderung vorgenommen.')
        return 1
    return 0

if __name__ == '__main__': raise SystemExit(main())
