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
from ranking_provision import NAME
import ranking_backup as backup


class BackupFailure(Exception):
    pass


# Fixed labels only; never interpolate exception text, inputs, or secrets into logs.
MESSAGES = {
    'request': 'Workflow nicht auf main manuell bestätigt.',
    'credentials': 'Cloudflare-Konfiguration unvollständig oder ungültig.',
    'password_missing': 'GitHub-Secret RANKING_BACKUP_PASSPHRASE fehlt oder ist für diesen Workflow nicht verfügbar.',
    'password_format': 'GitHub-Secret RANKING_BACKUP_PASSPHRASE hat nicht das geforderte Format.',
    'path': 'Temporäres Backupziel ungültig oder bereits vorhanden.',
    'capture': 'Lesen oder Validierung des Produktions-Snapshots fehlgeschlagen.',
    'encryption': 'Verschlüsselung, Schreiben oder Rückprüfung fehlgeschlagen.',
}


def run(env, opener):
    account = env.get('CLOUDFLARE_ACCOUNT_ID', '').strip()
    token = env.get('CLOUDFLARE_API_TOKEN', '').strip()
    password = env.get('RANKING_BACKUP_PASSPHRASE', '')
    temp_dir = env.get('RUNNER_TEMP', '')
    if (env.get('GITHUB_REF') != 'refs/heads/main' or env.get('GITHUB_EVENT_NAME') != 'workflow_dispatch'
            or env.get('CONFIRM_BACKUP') != 'true'):
        raise BackupFailure('request')
    if not re.fullmatch(r'[0-9a-fA-F]{32}', account) or not token or '\n' in token or '\r' in token:
        raise BackupFailure('credentials')
    if not password: raise BackupFailure('password_missing')
    if not re.fullmatch(r'[A-Za-z0-9_-]{43}', password): raise BackupFailure('password_format')
    try:
        if not temp_dir or not Path(temp_dir).is_absolute() or not Path(temp_dir).is_dir():
            raise ValueError('invalid temp directory')
        target = backup.safe_path(str(Path(temp_dir) / 'ranking-backup.rankbackup'))
        if target.exists() or target.is_symlink(): raise ValueError('destination exists')
    except Exception:
        raise BackupFailure('path') from None
    try:
        data = backup.capture(opener, 'https://api.cloudflare.com/client/v4/accounts/' + account, token, DB_ID, NAME)
    except Exception:
        raise BackupFailure('capture') from None
    try:
        blob = backup.encrypt(data, password)
        backup.write_backup(target, blob, password, DB_ID, NAME)
        if backup.decrypt(target.read_bytes(), password, DB_ID, NAME) != data:
            raise ValueError('verification mismatch')
    except Exception:
        raise BackupFailure('encryption') from None
    print('::notice::Verschlüsselter Snapshot erstellt und authentifiziert geprüft. Keine Datenbank geändert.')
    print('::warning::Artifact ist zeitlich begrenzt und kein Löschjournal. Drive-Upload und unabhängige Prüfung stehen aus.')


def main():
    try:
        run(os.environ, build_opener(ProxyHandler({}), NoRedirect()))
    except BackupFailure as exc:
        print('::error::' + MESSAGES.get(exc.args[0] if exc.args else '', 'Ranglisten-Sicherung nicht bestätigt.'))
        return 1
    except Exception:
        print('::error::Ranglisten-Sicherung nicht bestätigt; keine Inhalte oder Zugangsdaten ausgeben.')
        return 1
    return 0


if __name__ == '__main__': sys.exit(main())
