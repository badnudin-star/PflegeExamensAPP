#!/usr/bin/env python3
"""Report whether the opted-in quiz generation has all required settings."""
import os
from pathlib import Path
import sys

REQUIRED = ('FOLDER', 'MODEL', 'GOOGLE_CREDS', 'GEMINI_API_KEY')


def main():
    enabled = os.environ.get('ENABLED', '').strip()
    if enabled in ('', 'false'):
        ready, message, code = False, 'Quizgenerierung deaktiviert; kein Entwurf erstellt.', 0
    elif enabled != 'true':
        ready, message, code = False, 'QUIZ_GENERATION_ENABLED muss true oder false sein.', 1
    else:
        missing = [name for name in REQUIRED if not os.environ.get(name, '').strip()]
        if missing:
            ready, message, code = False, 'Quizgenerierung aktiviert, aber Konfiguration unvollständig: ' + ', '.join(missing), 1
        else:
            ready, message, code = True, 'Quizgenerierung aktiviert; Konfiguration vorhanden (Zugänge noch nicht geprüft).', 0
    print(message)
    if os.environ.get('GITHUB_OUTPUT'):
        with Path(os.environ['GITHUB_OUTPUT']).open('a', encoding='utf-8') as f:
            f.write(f'ready={str(ready).lower()}\n')
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a', encoding='utf-8') as f:
            f.write('### Tagesquiz\n\n' + message + '\n')
    return code


if __name__ == '__main__':
    sys.exit(main())
