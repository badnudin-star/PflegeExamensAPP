#!/usr/bin/env python3
"""Read the revised 42-topic workbook without publishing or modifying it.

Card IDs are derived from topic, question and answer, not from workbook row numbers.
An edited card consequently gets a new ID; callers must explicitly handle removal
of the old ID rather than silently transferring a learner's progress.
"""
import hashlib
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'learning_import'))
from import_notes import TOPICS

FILENAME = 'Karteikarten_42_Themen_ueberarbeitet.xlsx'
# Workbook order differs from the application's fixed topic order. These mappings
# are explicit: neither row position nor a similarity heuristic assigns topics.
TOPIC_MAP = [
 ('Altersgerechte Kommunikation', 'Altersgerechte_Kommunikation', 1),
 ('Atopische Dermatitis', 'Atopische_Dermatitis', 2),
 ('Beratung Information Schulung', 'Beratung_Information_Schulung', 15),
 ('Corbin Strauss', 'Corbin_Strauss', 16),
 ('Entwicklung Erikson', 'Entwicklung_Erikson', 3),
 ('Ernaehrungsmanagement', 'Ernaehrungsmanagement', 17),
 ('Ethische Prinzipien und Dilemma', 'Ethische_Prinzipien_und_Dilemma', 28),
 ('Expertenstandards', 'Expertenstandards', 18),
 ('Fallbesprechung', 'Fallbesprechung', 29),
 ('Gesundheitsfoerderung', 'Gesundheitsfoerderung', 19),
 ('Handeln im Notfall', 'Handeln_im_Notfall', 31),
 ('Herzinsuffizienz', 'Herzinsuffizienz', 4),
 ('Hypoxischer Hirnschaden', 'Hypoxischer_Hirnschaden', 30),
 ('Konflikteskalation Glasl', 'Konflikteskalation_Glasl', 5),
 ('Laienpflege Professionelle Pflege', 'Laienpflege_Professionelle_Pflege', 6),
 ('Leben im Familienalltag', 'Leben_im_Familienalltag', 7),
 ('Lungenembolie', 'Lungenembolie', 32),
 ('Lymphoedem', 'Lymphoedem', 20),
 ('Mammakarzinom', 'Mammakarzinom', 21),
 ('NANDA Pflegediagnosen', 'NANDA_Pflegediagnosen', 8),
 ('Nosokomiale Infektion', 'Nosokomiale_Infektion', 33),
 ('Palliativpflege', 'Palliativpflege', 34),
 ('Patientenverfuegung', 'Patientenverfuegung', 35),
 ('Pflege bei Chemotherapie', 'Pflege_bei_Chemotherapie', 22),
 ('Pflege bei Fieber', 'Pflege_bei_Fieber', 36),
 ('Pflegebeduerftigkeit', 'Pflegebeduerftigkeit', 12),
 ('Pflegeethik', 'Pflegeethik', 37),
 ('Pflegeforschung', 'Pflegeforschung', 23),
 ('Pflegende Angehoerige', 'Pflegende_Angehoerige', 13),
 ('Praevention', 'Praevention', 24),
 ('Qualitaetssicherung', 'Qualitaetssicherung', 14),
 ('Reanimation', 'Reanimation', 38),
 ('Rheumatoide Arthritis', 'Rheumatoide_Arthritis', 25),
 ('S3 Leitlinie', 'S3_Leitlinie', 11),
 ('SGB V und SGB XI', 'SGB_V_und_SGB_XI', 9),
 ('Schenkelhalsfraktur', 'Schenkelhalsfraktur', 39),
 ('Sondenkost', 'Sondenkost', 41),
 ('Soziale Rolle', 'Soziale_Rolle', 10),
 ('Sturzprophylaxe', 'Sturzprophylaxe', 26),
 ('Thrombose', 'Thrombose', 40),
 ('Vorsorge', 'Vorsorge', 27),
 ('Wundmanagement', 'Wundmanagement', 42),
]
HEADERS = ('Nr.', 'Thema', 'Frage', 'Antwort', 'Grundlage: ausführlicher Lernzettel')
OVERVIEW_HEADERS = ('Nr.', 'Thema / zum Themenblatt', 'Karten', 'Grundlage (ausführlicher Lernzettel)')


def _values(row, width):
    cells = list(row)
    if any(c.data_type == 'f' for c in cells):
        raise ValueError('Formeln im Kartenverzeichnis sind nicht erlaubt')
    values = [c.value for c in cells]
    if any(v is not None for v in values[width:]):
        raise ValueError('Unerwartete zusätzliche Daten im Kartenverzeichnis')
    return (values + [None] * width)[:width]


def _text(value):
    if not isinstance(value, str) or not value.strip():
        raise ValueError('Kartenfeld fehlt oder ist kein Text')
    return value.strip()


def _card_id(topic, question, answer):
    # Canonical JSON avoids ambiguous concatenations; a change never reuses an ID.
    value = json.dumps([topic, question, answer], ensure_ascii=False, separators=(',', ':'))
    return 'card-v2-' + hashlib.sha256(value.encode('utf-8')).hexdigest()


def read_revised_cards(path):
    from openpyxl import load_workbook
    book = load_workbook(path, read_only=True, data_only=False)
    try:
        if 'Übersicht' not in book.sheetnames or 'Alle Karten' not in book.sheetnames:
            raise ValueError('Übersicht oder Kartenverzeichnis fehlt')
        overview = list(book['Übersicht'].iter_rows())
        headers = [i for i, row in enumerate(overview) if tuple(_values(row, 4)) == OVERVIEW_HEADERS]
        if len(headers) != 1:
            raise ValueError('Übersicht hat keine eindeutige Kopfzeile')
        entries = []
        for row in overview[headers[0] + 1:]:
            values = _values(row, 4)
            if all(v is None for v in values):
                continue
            entries.append(values)
        if len(entries) != 42:
            raise ValueError('Übersicht muss genau 42 Themen enthalten')
        expected = {}
        for index, ((name, slug, topic_number), row) in enumerate(zip(TOPIC_MAP, entries), 1):
            number, title, count, source = row
            filename = slug + '_Neu.pdf'
            if number != index or title != name or source != filename or type(count) is not int or not 1 <= count <= 500:
                raise ValueError(f'Ungültiger Übersichtseintrag {index}')
            expected[index] = (name, filename, TOPICS[topic_number - 1][1], count)
        rows = iter(book['Alle Karten'].iter_rows())
        if tuple(_values(next(rows), 5)) != HEADERS:
            raise ValueError('Kartenverzeichnis hat eine unerwartete Kopfzeile')
        counts = {i: 0 for i in expected}
        result = []
        seen_ids = set()
        for row in rows:
            values = _values(row, 5)
            if all(v is None for v in values):
                continue
            number, name, question, answer, source = values
            if not isinstance(number, str) or not re.fullmatch(r'\d{2}\.\d{2,3}', number):
                raise ValueError('Ungültige Kartennummer')
            topic_number, card_number = map(int, number.split('.'))
            if topic_number not in expected:
                raise ValueError('Unbekannte Themennummer')
            expected_name, expected_source, topic, expected_count = expected[topic_number]
            counts[topic_number] += 1
            if card_number != counts[topic_number] or name != expected_name or source != expected_source:
                raise ValueError(f'Ungültige Zuordnung bei Karte {number}')
            question, answer = _text(question), _text(answer)
            card_id = _card_id(topic, question, answer)
            if card_id in seen_ids:
                raise ValueError('Doppelte Karte')
            seen_ids.add(card_id)
            result.append({'id': card_id, 'topic': topic, 'front': question, 'back': answer,
                           'sources': [f'{FILENAME} · Alle Karten · {number}', source]})
        if any(counts[i] != item[3] for i, item in expected.items()):
            raise ValueError('Kartenzahlen stimmen nicht mit der Übersicht überein')
        return result
    finally:
        book.close()
