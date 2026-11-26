#!/usr/bin/env python3
"""Validate the unsigned iPhone preview package; not an installability test."""
import json
import plistlib
from pathlib import PurePosixPath
import struct
import sys
import zipfile

APP = 'Payload/PflegeExamen.app/'
MAX_FILE = 20 * 1024 * 1024


def verify(path):
    with zipfile.ZipFile(path) as archive:
        files = archive.infolist()
        names = [entry.filename for entry in files]
        if len(names) != len(set(names)) or not files:
            raise ValueError('Doppelte oder fehlende ZIP-Einträge')
        for entry in files:
            name = entry.filename
            parts = PurePosixPath(name).parts
            if name.startswith('/') or '..' in parts or (name != 'Payload/' and not name.startswith(APP)):
                raise ValueError('Unerwarteter Paketinhalt')
            if entry.file_size > MAX_FILE:
                raise ValueError('Zu große Datei im Paket')
        if any('embedded.mobileprovision' in n or '/_CodeSignature/' in n for n in names):
            raise ValueError('Signatur oder Provisioning-Profil unerwartet enthalten')
        info = plistlib.loads(archive.read(APP + 'Info.plist'))
        if (info.get('CFBundleIdentifier') != 'de.pflegeexamen.ios.preview' or
                info.get('CFBundleExecutable') != 'PflegeExamen' or
                'iPhoneOS' not in info.get('CFBundleSupportedPlatforms', [])):
            raise ValueError('Keine erwartete iPhone-Vorschau')
        # iPhoneOS device binary, not simulator; natively built arm64 Mach-O.
        executable = archive.read(APP + 'PflegeExamen')
        if len(executable) < 12 or struct.unpack('<II', executable[:8]) != (0xfeedfacf, 0x0100000c):
            raise ValueError('Kein einzelnes arm64-Mach-O für iPhone')
        catalog = json.loads(archive.read(APP + 'learning_content.json'))
        if catalog.get('schemaVersion') != 1 or not catalog.get('notes'):
            raise ValueError('Offline-Katalog fehlt oder ist ungültig')
        return info, catalog


if __name__ == '__main__':
    info, catalog = verify(sys.argv[1])
    print(f"Nicht signierte iPhone-Vorschau {info['CFBundleShortVersionString']} "
          f"(Build {info['CFBundleVersion']}), Katalogrevision {catalog['revision']}. "
          'Format geprüft; Installation mit AltStore noch nicht getestet.')
