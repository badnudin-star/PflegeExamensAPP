#!/usr/bin/env python3
"""Import the two approved workbooks into an existing catalog; never modify the XLSX."""
import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'learning_import'))
from import_notes import TOPICS, MAX_CATALOG, save_atomic

# Exact headings checked against the workbooks, not inferred from sheet order.
HEADINGS = [
 'Altersgerechte Kommunikation','Atopische Dermatitis (Neurodermitis)','Entwicklung nach Erikson',
 'Herzinsuffizienz','Konflikteskalation nach Glasl','Laienpflege und professionelle Pflege',
 'Leben im Familienalltag','NANDA-Pflegediagnosen','SGB V und SGB XI','Soziale Rolle',
 'S3-Leitlinie','Pflegebedürftigkeit','Pflegende Angehörige und Belastung','Qualitätssicherung',
 'Beratung auch kollegial','Corbin-Strauss','Ernährungsmanagement und Expertenstandard',
 'Expertenstandards allgemein','Gesundheitsförderung','Lymphödem','Mammakarzinom',
 'Pflege bei Chemotherapie','Pflegeforschung','Prävention','Rheumatoide Arthritis',
 'Sturz und Sturzprophylaxe','Vorsorge','Ethische Prinzipien und Dilemma','Fallbesprechung',
 'Hypoxischer Hirnschaden','Handeln im Notfall','Lungenembolie','Nosokomiale Infektion',
 'Palliativpflege','Patientenverfügung','Pflege bei Fieber','Pflegeethik',
 'Reanimation – Erwachsene und Kinder','Schenkelhalsfraktur','Tiefe Venenthrombose',
 'Umgang mit Sondenkost','Wundmanagement',
]
GROUP_12 = {1,2,3,4,8,22,26,28,32,33,36,40}
FILES = {
 'Karteikarten_12_Themen_abgeglichen.xlsx': GROUP_12,
 'Karteikarten_30_Themen_Teilstand.xlsx': set(range(1,43)) - GROUP_12,
}


def read_cards(paths):
    from openpyxl import load_workbook
    if set(paths) != set(FILES): raise ValueError('Genau die beiden aktuellen Arbeitsmappen sind erforderlich.')
    result = []
    seen_topics = set()
    for name, expected in FILES.items():
        workbook = load_workbook(paths[name], read_only=True, data_only=False)
        try:
            for sheet in workbook.worksheets:
                if sheet.title == 'Übersicht': continue
                match = re.match(r'^(\d{2})_', sheet.title)
                if not match: raise ValueError(f'Unbekanntes Themenblatt: {sheet.title}')
                number = int(match.group(1))
                if number not in expected or number in seen_topics: raise ValueError(f'Doppeltes oder falsches Themenblatt: {sheet.title}')
                rows = sheet.iter_rows(values_only=False)
                first = next(rows)[0].value
                if first != f'{number:02d}  {HEADINGS[number - 1]}': raise ValueError(f'Blatttitel stimmt nicht: {sheet.title}')
                cards = []
                for row in rows:
                    values = [cell.value for cell in row[:4]]
                    if len(values) < 4: values += [None] * (4 - len(values))
                    if type(values[0]) is not int:
                        if any(isinstance(v,str) and v.startswith('=') for v in values): raise ValueError(f'Formel im Themenblatt: {sheet.title}')
                        continue
                    index, front, back, source = values
                    if index != len(cards) + 1 or any(not isinstance(x, str) or not x.strip() or x.startswith('=') for x in (front, back, source)):
                        raise ValueError(f'Ungültige oder doppelte Karte: {sheet.title}, {index}')
                    cards.append({'id':f'card-{number:02d}-{index:03d}', 'topic':TOPICS[number-1][1],
                                  'front':front.strip(), 'back':back.strip(),
                                  'sources':[f'{name} · {sheet.title} · Karte {index}', source.strip()]})
                if not cards: raise ValueError(f'Keine Karten in {sheet.title}')
                seen_topics.add(number)
                result.extend(cards)
        finally:
            workbook.close()
    if seen_topics != set(range(1,43)): raise ValueError('Es fehlen Themenblätter.')
    return sorted(result,key=lambda c:c['id'])


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--directory',required=True,type=Path)
    p.add_argument('--base',required=True,type=Path)
    p.add_argument('--output',required=True,type=Path)
    args=p.parse_args()
    if args.base.resolve() == args.output.resolve(): p.error('Basis und Ausgabe müssen verschieden sein')
    catalog=json.loads(args.base.read_text(encoding='utf-8'))
    if catalog.get('schemaVersion') != 1 or type(catalog.get('revision')) is not int or any(not isinstance(catalog.get(k),list) for k in ('notes','cards','cases','quizzes')):
        raise ValueError('Ungültiger oder unvollständiger Basiskatalog')
    catalog['cards']=read_cards({name:args.directory/name for name in FILES})
    catalog['revision']+=1
    data=(json.dumps(catalog,ensure_ascii=False,indent=2)+'\n').encode()
    if len(data)>MAX_CATALOG: raise ValueError('Katalog überschreitet Größenlimit')
    save_atomic(args.output,data)
    print(f"{len(catalog['cards'])} Karten für 42 Themen importiert")

if __name__ == '__main__': main()
