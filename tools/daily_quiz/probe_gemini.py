#!/usr/bin/env python3
"""Two bounded synthetic Gemini requests; no Drive, source content, or publication."""
import json
import os
from pathlib import Path
import re
import sys

from quiz_pipeline import SCHEMA

MAX_RESPONSE_BYTES = 256 * 1024
TIMEOUT_SECONDS = 45


def request_status(http, key, model, body):
    """Report only an HTTP code and fixed response category, never server text."""
    response = None
    try:
        response = http.post(
            f'https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent',
            headers={'x-goog-api-key': key, 'Content-Type': 'application/json'},
            json=body, timeout=TIMEOUT_SECONDS, allow_redirects=False, stream=True,
        )
        code = response.status_code
        if type(code) is not int or not 100 <= code <= 599:
            return 'ungültiger HTTP-Status'
        if code != 200:
            return f'HTTP {code}'
        chunks, total = [], 0
        for chunk in response.iter_content(chunk_size=4096):
            total += len(chunk)
            if total > MAX_RESPONSE_BYTES:
                return 'HTTP 200, Antwort zu groß'
            chunks.append(chunk)
        data = json.loads(b''.join(chunks).decode('utf-8', errors='strict'))
        parts = data['candidates'][0]['content']['parts']
        if not isinstance(parts, list) or not any(isinstance(p, dict) and isinstance(p.get('text'), str) and p['text'] for p in parts):
            return 'HTTP 200, kein auswertbarer Antworttext'
        return 'HTTP 200, Antworttext vorhanden'
    except Exception:
        # Never print exceptions: they can include credentials, URLs, or provider details.
        return 'Verbindung oder Antwort nicht auswertbar'
    finally:
        if response is not None:
            try:
                response.close()
            except Exception:
                pass


def probe(http, key, model):
    if not key or not re.fullmatch(r'[A-Za-z0-9._-]+', model):
        return None
    # These texts contain no user data and produce no artifact or publication.
    basic = {'contents': [{'parts': [{'text': 'Antworte kurz mit einem Gruß.'}]}]}
    schema = {
        'contents': [{'parts': [{'text': (
            'Technischer Test mit künstlichen Daten: Ein Stoff namens Blau ist blau. '
            'Erstelle eine Multiple-Choice-Frage dazu, vier verschiedene Optionen, '
            'nullbasierten correctIndex, Erklärung und evidenceId 1. Antworte als JSON-Array.'
        )}]}],
        'generationConfig': {'responseMimeType': 'application/json', 'responseSchema': SCHEMA},
    }
    return (request_status(http, key, model, basic), request_status(http, key, model, schema))


def main():
    if os.environ.get('CONFIRMED', '').strip() != 'true':
        print('::error title=Gemini-Test::Test nicht bestätigt; keine Anfrage gesendet.')
        return 1
    # requests is imported only in the entry point; tests can inject a fake transport.
    import requests
    results = probe(requests, os.environ.get('GEMINI_API_KEY', ''), os.environ.get('GEMINI_MODEL', ''))
    if results is None:
        print('::error title=Gemini-Test::Modellname oder Zugangskonfiguration ungültig; keine Anfrage gesendet.')
        return 1
    lines = [
        '### Gemini-Test mit ausschließlich künstlichem Inhalt', '',
        f'- Einfacher Aufruf: {results[0]}.',
        f'- Aufruf mit Quiz-JSON-Schema: {results[1]}.',
        '- Höchstens zwei Anfragen; keine automatische Wiederholung, kein Drive-Zugriff, keine Veröffentlichung.',
        '- Ein Erfolg beweist weder die Verarbeitung echter Lernzettel noch fachliche Richtigkeit oder dauerhafte Verfügbarkeit.',
    ]
    print('\n'.join(lines))
    for line in lines[2:4]:
        print('::notice title=Gemini-Test::' + line[2:])
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a', encoding='utf-8') as f:
            f.write('\n'.join(lines) + '\n')
    return 0 if all(item == 'HTTP 200, Antworttext vorhanden' for item in results) else 1


if __name__ == '__main__':
    sys.exit(main())
