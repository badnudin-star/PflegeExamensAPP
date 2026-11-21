#!/usr/bin/env python3
"""Fail closed unless publication inputs include explicit review confirmation."""
import os
import re
import sys


def main():
    if os.environ.get('CONFIRMED') != 'true':
        print('Fachliche Prüfung nicht bestätigt; keine Veröffentlichung.', file=sys.stderr)
        return 1
    if not re.fullmatch(r'[1-9][0-9]{0,19}', os.environ.get('RUN_ID', '')):
        print('Ungültige Laufnummer; keine Veröffentlichung.', file=sys.stderr)
        return 1
    if not re.fullmatch(r'[0-9a-fA-F]{64}', os.environ.get('HASH', '')):
        print('Ungültige SHA-256-Prüfsumme; keine Veröffentlichung.', file=sys.stderr)
        return 1
    print('Prüfung bestätigt. Die Prüfsumme und der Inhalt werden vor Veröffentlichung überprüft.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
