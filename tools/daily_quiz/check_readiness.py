#!/usr/bin/env python3
"""Read-only configuration presence report; never contacts Drive, Gemini or Cloudflare."""
import os
from pathlib import Path

# Environment names are private to the workflow. Only these fixed public labels are printed.
REQUIRED = (
    ('FOLDER', 'Variable LEARNING_DRIVE_FOLDER_ID'),
    ('MODEL', 'Variable GEMINI_MODEL'),
    ('GOOGLE_CREDS', 'Secret LEARNING_DRIVE_CREDENTIALS'),
    ('GEMINI_API_KEY', 'Secret GEMINI_API_KEY'),
)


def main():
    enabled = os.environ.get('ENABLED', '').strip().lower()
    state = {'': 'deaktiviert (Variable nicht gesetzt)', 'false': 'deaktiviert',
             'true': 'aktiviert'}.get(enabled, 'ungültig')
    # Fixed diagnostic categories only, never echo the untrusted variable value.
    hint = None
    if state == 'ungültig':
        if len(enabled) >= 2 and enabled[0] == enabled[-1] and enabled[0] in ('\"', "'") and enabled[1:-1] in ('true', 'false'):
            hint = 'Anführungszeichen gehören nicht zum Wert'
        elif enabled.lower() in ('1', '0', 'yes', 'no', 'ja', 'nein', 'on', 'off'):
            hint = 'Zahlen und Ja/Nein-Werte werden nicht unterstützt'
        else:
            hint = 'anderes ungültiges Format'
    missing = [label for key, label in REQUIRED if not os.environ.get(key, '').strip()]
    lines = [
        '### Tagesquiz: schreibgeschützte Konfigurationsprüfung', '',
        f'- Generierungsschalter: {state}.',
        '- Es wurden keine Inhalte übertragen, keine Verbindungen getestet und keine Einstellungen geändert.',
    ]
    if hint:
        lines.append(f'- Hinweis zum Generierungsschalter: {hint}.')
    for key, label in REQUIRED:
        status = 'vorhanden' if os.environ.get(key, '').strip() else 'fehlt oder ist leer'
        lines.append(f'- {label}: {status}.')
    lines.extend(['', 'Vorhandene Werte wurden nicht auf Gültigkeit geprüft. '
                  'Die regelmäßige Generierung und Veröffentlichung benötigen eigene Freigaben.'])
    report = '\n'.join(lines) + '\n'
    print(report, end='')
    # Fixed status text only; annotations remain readable without downloading job logs.
    for line in lines:
        if line.startswith('- ') and not line.startswith('- Es wurden'):
            print('::notice title=Tagesquiz::' + line[2:])
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with Path(summary).open('a', encoding='utf-8') as f:
            f.write(report)
    # Disabled is an intentional valid configuration even if optional secrets are absent.
    if state == 'ungültig':
        print('::error title=Tagesquiz::QUIZ_GENERATION_ENABLED muss true, false oder leer sein.')
        return 1
    if enabled == 'true' and missing:
        print('::error title=Tagesquiz::Aktivierte Generierung hat unvollständige Konfiguration; siehe Zusammenfassung.')
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
