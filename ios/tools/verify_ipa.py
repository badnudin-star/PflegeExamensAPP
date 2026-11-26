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


def verify(path, pdf_directory=None):
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
        # Canary for the simulator-only synthetic UI entry point. This catches
        # this specific accidental test-flag leak; it is not a general proof
        # that a binary contains no other test code or unsafe features.
        if b'-synthetic-ranking-ui' in executable:
            raise ValueError('Simulator-Testoberfläche darf nicht in die iPhone-IPA')
        catalog = json.loads(archive.read(APP + 'learning_content.json'))
        if catalog.get('schemaVersion') != 1 or not catalog.get('notes'):
            raise ValueError('Offline-Katalog fehlt oder ist ungültig')
        if pdf_directory is not None:
            from pathlib import Path
            directory = Path(pdf_directory)
            expected = {item.name for item in directory.iterdir() if item.is_file()}
            prefix = APP + 'note_pdfs/'
            actual = {name[len(prefix):] for name in names if name.startswith(prefix) and not name.endswith('/')}
            if not expected or actual != expected or 'manifest.json' not in expected:
                raise ValueError('IPA-PDF-Verzeichnis unvollständig')
            for name in expected:
                if archive.read(prefix + name) != (directory / name).read_bytes():
                    raise ValueError('IPA-PDF-Datei weicht ab')
        return info, catalog


if __name__ == '__main__':
    info, catalog = verify(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
    print(f"Nicht signierte iPhone-Vorschau {info['CFBundleShortVersionString']} "
          f"(Build {info['CFBundleVersion']}), Katalogrevision {catalog['revision']}. "
          'Format geprüft; Installation mit SideStore noch nicht getestet.')
