#!/usr/bin/env python3
"""Render a read-only, offline review sheet for an unchanged quiz draft."""
import argparse
import hashlib
import html
import json
from pathlib import Path

from quiz_pipeline import validate_draft
from import_notes import save_atomic


def render(raw):
    draft = validate_draft(json.loads(raw))
    digest = hashlib.sha256(raw).hexdigest()
    esc = lambda value: html.escape(value, quote=True)
    cards = []
    for number, q in enumerate(draft['quizzes'], 1):
        options = ''.join(
            '<li' + (' class="correct"' if i == q['correctIndex'] else '') + '>' + esc(option)
            + (' <strong>– als richtig markiert</strong>' if i == q['correctIndex'] else '') + '</li>'
            for i, option in enumerate(q['options'])
        )
        cards.append(f'''<section class="question">
<h2>{number}. {esc(q['topic'])}</h2>
<p class="question-text">{esc(q['question'])}</p>
<ol type="A">{options}</ol>
<p><strong>Erklärung:</strong> {esc(q['explanation'])}</p>
<p><strong>Quelle und Beleg:</strong><br>{'<br>'.join(esc(s) for s in q['sources'])}</p>
<p class="check">☐ Frage, Lösung, Ablenker, Erklärung und Beleg fachlich geprüft</p>
</section>''')
    return f'''<!doctype html>
<html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Fachliche Prüfung – Tagesquiz {esc(draft['date'])}</title>
<style>
body{{font: 16px/1.5 system-ui,sans-serif; color:#182422; max-width: 900px; margin: 2rem auto; padding:0 1rem}}
h1{{line-height:1.2}} h2{{font-size:1.1rem}} .notice{{padding:1rem; background:#fff4d6; border-left:4px solid #986400}}
.question{{border:1px solid #cdd9d5; border-radius:8px; padding:1rem; margin:1rem 0; break-inside:avoid}}
.question-text{{font-weight:600}} li{{margin:.3rem 0}} .correct{{color:#075d38}} .check{{border-top:1px solid #ddd; padding-top:.6rem}}
code{{overflow-wrap:anywhere}} @media print{{body{{font-size:11pt}} .question{{border-radius:0}}}}
</style></head><body>
<h1>Fachliche Prüfung – Tagesquiz {esc(draft['date'])}</h1>
<div class="notice"><strong>Nur Prüfhilfe, keine automatische Freigabe.</strong> Alle 50 Fragen und die Originalquellen fachlich prüfen. Ein wörtliches Zitat belegt weder die medizinische Richtigkeit noch die Eindeutigkeit der Antwort. Bei Fehlern den Entwurf nicht veröffentlichen. Die Kästchen sind nur eine Druckhilfe und werden nicht gespeichert.</div>
<p>SHA-256 der unveränderten <code>quiz-draft.json</code>:<br><code>{digest}</code></p>
<p>Die Prüfansicht enthält Lösungen und Quellenauszüge. Sie kann wie das JSON-Artifact öffentlich zugänglich sein. Sie bearbeitet oder veröffentlicht nichts.</p>
{''.join(cards)}
</body></html>'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('draft', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    if args.draft.resolve() == args.output.resolve():
        parser.error('Prüfansicht und Entwurf müssen verschiedene Dateien sein')
    rendered = render(args.draft.read_bytes())
    save_atomic(args.output, rendered.encode('utf-8'))


if __name__ == '__main__':
    main()
