#!/usr/bin/env python3
"""Create a local SideStore-compatible draft; never uploads or publishes it."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import re
import struct
from urllib.parse import urlsplit
import zipfile
from verify_ipa import APP, verify


def https_url(value, suffix):
    url = urlsplit(value)
    if (url.scheme != 'https' or not url.hostname or url.username or url.password or
            url.query or url.fragment or not url.path.lower().endswith(suffix)):
        raise ValueError('Eine direkte HTTPS-Datei-URL ohne Zugangsdaten oder Parameter ist erforderlich')
    return value


def check_unsigned_executable(raw):
    # Fail closed if the executable contains a code-signature load command. The
    # source manifest below assumes no embedded entitlements, frameworks, or extensions.
    if len(raw) < 32:
        raise ValueError('Ungültiger Mach-O-Header')
    magic, cpu, _, _, commands, size, _, _ = struct.unpack('<IIIIIIII', raw[:32])
    if magic != 0xfeedfacf or cpu != 0x0100000c or commands > 4096 or 32 + size > len(raw):
        raise ValueError('Ungültige Mach-O-Ladebefehle')
    offset, end = 32, 32 + size
    for _ in range(commands):
        if offset + 8 > end: raise ValueError('Unvollständiger Mach-O-Ladebefehl')
        cmd, length = struct.unpack('<II', raw[offset:offset + 8])
        if length < 8 or offset + length > end: raise ValueError('Ungültiger Mach-O-Ladebefehl')
        if cmd == 0x1d: raise ValueError('Signiertes Mach-O: Entitlements vor Veröffentlichung prüfen')
        offset += length
    if offset != end: raise ValueError('Unerwartete Mach-O-Ladebefehle')


def make_source(ipa, download_url, icon_url, date):
    download_url = https_url(download_url, '.ipa')
    icon_url = https_url(icon_url, '.png')
    if datetime.date.fromisoformat(date).isoformat() != date:
        raise ValueError('Datum muss JJJJ-MM-TT sein')
    info, _ = verify(ipa)
    version, build, minimum = (info.get(k) for k in ('CFBundleShortVersionString', 'CFBundleVersion', 'MinimumOSVersion'))
    if not all(isinstance(v, str) and re.fullmatch(r'[0-9]+(?:\.[0-9]+){0,2}', v) for v in (version, build, minimum)):
        raise ValueError('Ungültige Versionsangaben im IPA')
    with zipfile.ZipFile(ipa) as z:
        if any(n.startswith(APP + prefix) for n in z.namelist() for prefix in ('Frameworks/', 'PlugIns/', 'Watch/', 'AppClips/')):
            raise ValueError('Frameworks und Erweiterungen müssen gesondert auf Berechtigungen geprüft werden')
        check_unsigned_executable(z.read(APP + 'PflegeExamen'))
    privacy = {k: v for k, v in info.items() if k.startswith('NS') and k.endswith('UsageDescription')}
    if any(not isinstance(v, str) or not v.strip() for v in privacy.values()):
        raise ValueError('Ungültige Datenschutzerklärung in Info.plist')
    return {
        'name': 'PflegeExamen Vorschau', 'identifier': 'de.pflegeexamen.ios.preview.source',
        'apps': [{
            'name': 'PflegeExamen Vorschau', 'bundleIdentifier': info['CFBundleIdentifier'],
            'developerName': 'PflegeExamen', 'subtitle': 'Technische iPhone-Vorschau',
            'localizedDescription': 'Unvollständige Vorschau. Ranglistenteilnahme und Gerätetest fehlen; Original-PDFs nur im speziell gebündelten Build.',
            'iconURL': icon_url, 'tintColor': '0E6B6E',
            'versions': [{'version': version, 'buildVersion': build, 'date': date,
                          'localizedDescription': 'Nicht auf einem iPhone getestete Vorschau.',
                          'downloadURL': download_url, 'size': Path(ipa).stat().st_size, 'minOSVersion': minimum}],
            'appPermissions': {'entitlements': [], 'privacy': privacy},
        }], 'news': [],
    }


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--ipa', required=True, type=Path)
    p.add_argument('--ipa-url', required=True)
    p.add_argument('--icon-url', required=True)
    p.add_argument('--date', required=True, help='Datum JJJJ-MM-TT')
    p.add_argument('--output', required=True, type=Path)
    args = p.parse_args()
    source = make_source(args.ipa, args.ipa_url, args.icon_url, args.date)
    # Exclusive creation; never overwrite an existing source or publish anything.
    with args.output.open('x', encoding='utf-8') as out:
        json.dump(source, out, ensure_ascii=False, indent=2)
        out.write('\n')
    print('Lokaler Entwurf erstellt. IPA SHA-256:', hashlib.sha256(args.ipa.read_bytes()).hexdigest())
    print('Vor Veröffentlichung URL, Bild, Berechtigungen, Rechte und Gerätetest prüfen.')


if __name__ == '__main__':
    main()
