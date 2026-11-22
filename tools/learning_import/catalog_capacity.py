"""Report catalog size only; never inspect or expose questions or source text."""
import os
from pathlib import Path

from import_notes import MAX_CATALOG

LABELS = {
    'server': 'Abgerufener Inhaltskatalog',
    'bundle': 'APK-Offlinekatalog',
    'notes_candidate': 'Lernzettel-Katalogkandidat',
    'quiz_candidate': 'Quiz-Katalogkandidat',
}


def capacity(size, limit=MAX_CATALOG):
    if type(size) is not int or size < 0 or type(limit) is not int or limit <= 0:
        raise ValueError('Ungültige Größe')
    status = ('over' if size > limit else 'critical' if size * 100 >= limit * 95 else
              'warning' if size * 100 >= limit * 80 else 'ok')
    # Integer arithmetic avoids falsely rounding up to a warning threshold.
    tenths = size * 1000 // limit
    rest = f'{limit - size} Bytes frei' if size <= limit else f'{size - limit} Bytes über dem Limit'
    return status, f'{size} / {limit} Bytes ({tenths // 10},{tenths % 10} %) · {rest}.'


def report_capacity(size, category):
    if category not in LABELS:
        raise ValueError('Unbekannte Katalogart')
    status, detail = capacity(size)
    label = LABELS[category]
    message = f'{label}: {detail}'
    print(message, flush=True)
    if status != 'ok':
        warning = 'Katalog zu mindestens 80 % belegt; Archivierung ist noch nicht produktiv gelöst.'
        if status == 'critical':
            warning = 'Katalog zu mindestens 95 % belegt; Archivierung ist noch nicht produktiv gelöst.'
        elif status == 'over':
            warning = 'Katalog überschreitet das Größenlimit und kann nicht veröffentlicht werden.'
        if os.environ.get('GITHUB_ACTIONS') == 'true':
            print(f'::warning title=Kataloggröße::{warning}', flush=True)
        else:
            print('Warnung: ' + warning, flush=True)
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        try:
            with Path(summary).open('a', encoding='utf-8') as f:
                f.write(f'### Kataloggröße\n\n{message}\n')
                if status != 'ok':
                    f.write('Achtung: Die Archivierung ist noch nicht produktiv gelöst.\n')
                f.write('\n')
        except OSError:
            # Reporting is informational and must not change publication behavior.
            print('Kataloggröße konnte nicht in die Schrittzusammenfassung geschrieben werden.', flush=True)
