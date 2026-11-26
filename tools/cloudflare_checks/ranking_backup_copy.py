#!/usr/bin/env python3
"""Verify and copy an encrypted ranking backup; never restore or access the database.

A second copy is not an independent deletion journal. Local verification cannot
prove Google Drive has received a file or that a removable drive is durable.
"""
import getpass
import os
from pathlib import Path
import stat
import sys

import ranking_backup as backup
from ranking_initialize import DB_ID
from ranking_provision import NAME


def read_source(path):
    # Refuse a final-component symlink, a special file, or oversized data.
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, 'rb') as handle:
        meta = os.fstat(handle.fileno())
        if not stat.S_ISREG(meta.st_mode) or meta.st_size > backup.MAX_FILE:
            raise ValueError('invalid source')
        blob = handle.read(backup.MAX_FILE + 1)
    if len(blob) > backup.MAX_FILE: raise ValueError('source too large')
    return blob


def copy_verified(source, destination, password, source_id=DB_ID, source_name=NAME):
    source = backup.safe_path(str(source))
    destination = backup.safe_path(str(destination))
    if source == destination: raise ValueError('identical paths')
    blob = read_source(source)
    expected = backup.decrypt(blob, password, source_id, source_name)
    backup.write_backup(destination, blob, password, source_id, source_name)
    # Verify the final bytes, not just successfully decrypted equivalent data.
    copied = read_source(destination)
    if copied != blob or backup.decrypt(copied, password, source_id, source_name) != expected:
        # Keep a suspect file for investigation rather than deleting a potentially
        # replaced path; never report success or overwrite on retry.
        raise ValueError('copy verification failed')


def main():
    try:
        source = input('Vorhandene Sicherung (absoluter .rankbackup-Pfad): ').strip()
        destination = input('Neuer Zielpfad auf dem USB-Stick (absoluter .rankbackup-Pfad): ').strip()
        password = getpass.getpass('Sicherungs-Passphrase: ')
        copy_verified(source, destination, password)
        print('Verschlüsselte Kopie geschrieben und lokal authentifiziert sowie bytegleich geprüft.')
        print('Auf Google Drive muss die verschlüsselte Datei separat hochgeladen und dort geprüft werden.')
        print('Spätere Löschungen fehlen; kein produktiver Restore ohne vollständiges unabhängiges Löschjournal.')
    except (ValueError, OSError, TypeError, EOFError, UnicodeError, ImportError):
        print('Kopie nicht bestätigt. Eine teilweise geschriebene Zieldatei kann vorhanden sein; bitte prüfen. Nichts wiederhergestellt.')
        return 1
    return 0


if __name__ == '__main__': sys.exit(main())
