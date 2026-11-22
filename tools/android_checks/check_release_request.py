#!/usr/bin/env python3
"""Reject an unconfirmed release or a non-main branch without accessing secrets."""
import os
import sys


def validate(ref, confirmed):
    if ref != 'refs/heads/main':
        raise ValueError('Bitte beim Start des Workflows den Branch main wählen.')
    if confirmed != 'true':
        raise ValueError('Bitte beim Start das Bestätigungskästchen für die interne APK aktivieren.')


def main():
    try:
        validate(os.environ.get('GITHUB_REF', ''), os.environ.get('CONFIRMED', ''))
    except ValueError as exc:
        print('::error::' + str(exc))
        return 1
    print('Branch und ausdrückliche Bestätigung sind vorhanden.')
    return 0


if __name__ == '__main__': sys.exit(main())
