#!/usr/bin/env python3
"""Report whether the opted-in quiz generation has all required settings."""
import os
from pathlib import Path
import sys

REQUIRED = {
    'FOLDER': 'Variable LEARNING_DRIVE_FOLDER_ID',
    'MODEL': 'Variable GEMINI_MODEL',
    'GOOGLE_CREDS': 'Secret LEARNING_DRIVE_CREDENTIALS',
    'GEMINI_API_KEY': 'Secret GEMINI_API_KEY',
}


def main():
    args = sys.argv[1:]
    if args not in ([], ['--manual-test'], ['--manual-draft']):
        print('Ungültiger Prüfmodus.')
        return 1
    manual = bool(args)
    manual_label = 'Manueller Test' if args == ['--manual-test'] else 'Manueller Entwurf'
    # GitHub variables are strings; accept boolean spelling irrespective of case.
    enabled = os.environ.get('ENABLED', '').strip().lower()
    confirmed = os.environ.get('CONFIRMED', '').strip()
    if manual and confirmed != 'true':
        ready, message, code = False, manual_label + ' nicht bestätigt; keine Daten übertragen.', 1
    elif not manual and enabled in ('', 'false'):
        ready, message, code = False, 'Quizgenerierung deaktiviert; kein Entwurf erstellt.', 0
    elif not manual and enabled != 'true':
        ready, message, code = False, 'QUIZ_GENERATION_ENABLED muss true oder false sein.', 1
    else:
        missing = [label for name, label in REQUIRED.items() if not os.environ.get(name, '').strip()]
        label = manual_label if manual else 'Quizgenerierung'
        if missing:
            ready, message, code = False, label + ': Konfiguration unvollständig: ' + ', '.join(missing), 1
        elif os.environ.get('NO_BILLING_VERIFIED', '').strip().lower() != 'true':
            ready = False
            message = label + ': pausiert, weil ein kostenfreier Gemini-Zugang ohne Abrechnung nicht bestätigt ist; keine Anfrage gesendet.'
            code = 1 if manual else 0
        else:
            ready, message, code = True, label + ': Konfiguration und Kostenfreigabe vorhanden (Zugänge und Abrechnung nicht automatisch geprüft).', 0
    print(message)
    # Message text contains only fixed labels, never configuration values.
    if code:
        print('::error title=Tagesquiz::' + message)
    elif not ready and 'pausiert' in message:
        print('::warning title=Tagesquiz::' + message)
    if os.environ.get('GITHUB_OUTPUT'):
        with Path(os.environ['GITHUB_OUTPUT']).open('a', encoding='utf-8') as f:
            f.write(f'ready={str(ready).lower()}\n')
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a', encoding='utf-8') as f:
            f.write('### Tagesquiz\n\n' + message + '\n')
    return code


if __name__ == '__main__':
    sys.exit(main())
