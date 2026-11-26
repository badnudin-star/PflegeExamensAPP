#!/usr/bin/env python3
"""Create one encrypted, authenticated production ranking backup for a manual workflow.

No Drive upload or restore occurs. A GitHub Actions artifact is temporary storage,
not a verified durable backup. Never print data, secrets, or the passphrase.
"""
import os
from pathlib import Path
import re
import sys
from urllib.request import build_opener, ProxyHandler

from readiness import NoRedirect
from ranking_initialize import DB_ID
from ranking_provision import NAME, PreflightError
import ranking_backup as backup


def run(env, opener):
    account = env.get('CLOUDFLARE_ACCOUNT_ID', '').strip()
    token = env.get('CLOUDFLARE_API_TOKEN', '').strip()
    password = env.get('RANKING_BACKUP_PASSPHRASE', '')
    temp_dir = env.get('RUNNER_TEMP', '')
    if (env.get('GITHUB_REF') != 'refs/heads/main' or env.get('GITHUB_EVENT_NAME') != 'workflow_dispatch'
            or env.get('CONFIRM_BACKUP') != 'true' or not re.fullmatch(r'[0-9a-fA-F]{32}', account)
            or not token or '\n' in token or '\r' in token
            or not re.fullmatch(r'[A-Za-z0-9_-]{43}', password)
            or not temp_dir or not Path(temp_dir).is_absolute() or not Path(temp_dir).is_dir()):
        raise PreflightError('invalid configuration')
    target = backup.safe_path(str(Path(temp_dir) / 'ranking-backup.rankbackup'))
    if target.exists() or target.is_symlink(): raise PreflightError('backup path already exists')
    data = backup.capture(opener, 'https://api.cloudflare.com/client/v4/accounts/' + account, token, DB_ID, NAME)
    blob = backup.encrypt(data, password)
    backup.write_backup(target, blob, password, DB_ID, NAME)
    if backup.decrypt(target.read_bytes(), password, DB_ID, NAME) != data:
        raise PreflightError('verification mismatch')
    print('::notice::Verschlüsselter Snapshot erstellt und authentifiziert geprüft. Keine Datenbank geändert.')
    print('::warning::Artifact ist zeitlich begrenzt und kein Löschjournal. Drive-Upload und unabhängige Prüfung stehen aus.')


def main():
    try:
        run(os.environ, build_opener(ProxyHandler({}), NoRedirect()))
    except Exception:
        print('::error::Ranglisten-Sicherung nicht bestätigt; keine Inhalte oder Zugangsdaten ausgeben.')
        return 1
    return 0


if __name__ == '__main__': sys.exit(main())
