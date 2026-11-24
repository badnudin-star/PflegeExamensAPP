#!/usr/bin/env python3
"""Two bounded synthetic Gemini requests; no Drive, source content, or publication."""
import json
import os
from pathlib import Path
import re
import sys

from quiz_pipeline import SCHEMA, gemini_body, validate_generated

MAX_RESPONSE_BYTES = 256 * 1024
TIMEOUT_SECONDS = 120


def request_status(http, key, model, body, source=None):
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
        if source is not None:
            try:
                text = ''.join(p.get('text', '') for p in parts if isinstance(p, dict))
                items = json.loads(text)
                validate_generated(items, 'Fiktive Farbenwelt', 'synthetisch', source, '2026-01-01', 0)
            except (ValueError, TypeError, KeyError):
                return 'HTTP 200, Quizantwort technisch ungültig'
            return 'HTTP 200, zehn Quizfragen technisch gültig'
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


def synthetic_source(min_length):
    """Non-medical invented source; roughly the size of real learning sheets."""
    sentences = []
    for i in range(1, 501):
        color = ('blau', 'rot', 'grün', 'gelb', 'violett')[i % 5]
        sentences.append(f'In der erfundenen Farbenwelt trägt Figur Nummer {i} einen {color}en Hut. '
                         f'Ihr Fantasiewort lautet Farbwort{i} und ihre Zahl ist {i + 100}.')
        text = ' '.join(sentences)
        if len(text) >= min_length:
            return text
    raise ValueError('Künstlicher Text überschreitet die feste Grenze')


def sized_probe(http, key, model, sizes):
    if not key or not re.fullmatch(r'[A-Za-z0-9._-]+', model):
        return None
    results = []
    for size in sizes:
        source = synthetic_source(size)
        body = gemini_body('Fiktive Farbenwelt', source)
        results.append(request_status(http, key, model, body, source))
    return tuple(results)


def realistic_probe(http, key, model):
    return sized_probe(http, key, model, (5000, 12000))


def comparison_probe(http, key, model):
    """Vary one dimension at a time: source length vs. requested output count."""
    if not key or not re.fullmatch(r'[A-Za-z0-9._-]+', model):
        return None
    small = gemini_body('Fiktive Farbenwelt', synthetic_source(200))
    long = gemini_body('Fiktive Farbenwelt', synthetic_source(5000))
    text = long['contents'][0]['parts'][0]['text']
    if text.count('exakt 10 unterschiedliche') != 1:
        raise ValueError('Produktionsprompt nicht wie erwartet')
    long['contents'][0]['parts'][0]['text'] = text.replace('exakt 10 unterschiedliche', 'exakt 1 unterschiedliche', 1)
    return (request_status(http, key, model, small), request_status(http, key, model, long))


def main():
    if os.environ.get('CONFIRMED', '').strip() != 'true':
        print('::error title=Gemini-Test::Test nicht bestätigt; keine Anfrage gesendet.')
        return 1
    # requests is imported only in the entry point; tests can inject a fake transport.
    import requests
    profile = os.environ.get('PROBE_PROFILE', 'basic')
    if profile not in ('basic', 'realistic', 'compare', 'sizing'):
        print('::error title=Gemini-Test::Ungültiges Testprofil; keine Anfrage gesendet.')
        return 1
    run = {'basic': probe, 'realistic': realistic_probe, 'compare': comparison_probe,
           'sizing': lambda http, key, model: sized_probe(http, key, model, (1000, 2000))}[profile]
    results = run(requests, os.environ.get('GEMINI_API_KEY', ''), os.environ.get('GEMINI_MODEL', ''))
    if results is None:
        print('::error title=Gemini-Test::Modellname oder Zugangskonfiguration ungültig; keine Anfrage gesendet.')
        return 1
    labels = {
        'basic': ('Einfacher Aufruf', 'Aufruf mit Quiz-JSON-Schema'),
        'realistic': ('Künstliche Quelle ca. 5000 Zeichen', 'Künstliche Quelle ca. 12000 Zeichen'),
        'compare': ('Kurze Quelle, zehn Fragen angefordert', 'Quelle ca. 5000 Zeichen, eine Frage angefordert'),
        'sizing': ('Künstliche Quelle ca. 1000 Zeichen', 'Künstliche Quelle ca. 2000 Zeichen'),
    }[profile]
    lines = [
        '### Gemini-Test mit ausschließlich künstlichem Inhalt', '',
        f'- {labels[0]}: {results[0]}.',
        f'- {labels[1]}: {results[1]}.',
        '- Höchstens zwei Anfragen; keine automatische Wiederholung, kein Drive-Zugriff, keine Veröffentlichung.',
        '- Ein Erfolg beweist weder die Verarbeitung echter Lernzettel noch fachliche Richtigkeit oder dauerhafte Verfügbarkeit.',
    ]
    print('\n'.join(lines))
    for line in lines[2:4]:
        print('::notice title=Gemini-Test::' + line[2:])
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a', encoding='utf-8') as f:
            f.write('\n'.join(lines) + '\n')
    success = 'HTTP 200, zehn Quizfragen technisch gültig' if profile in ('realistic', 'sizing') else 'HTTP 200, Antworttext vorhanden'
    return 0 if all(item == success for item in results) else 1


if __name__ == '__main__':
    sys.exit(main())
