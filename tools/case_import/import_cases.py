#!/usr/bin/env python3
"""Import the user's numbered case PDFs without changing the original files."""
import argparse
import json
import re
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'learning_import'))
from import_notes import MAX_CATALOG, save_atomic

PAGE_HEADER = re.compile(r'(?m)^(?:FALLÜBUNG · (?:ERSTER|ZWEITER|DRITTER) PRÜFUNGSTAG|PRÜFUNGSVORBEREITUNG · FALLÜBUNG)\s*\n\d+\s*\n')
NUMBERED = re.compile(r'(?m)^(\d+) · ')
DAYS = {1: 'ERSTER', 2: 'ZWEITER', 3: 'DRITTER'}


def split_numbered(text):
    found = list(NUMBERED.finditer(text))
    if not found or [int(m.group(1)) for m in found] != list(range(1, len(found) + 1)):
        raise ValueError('Ungültige oder fehlende Aufgabennummern')
    preface = text[:found[0].start()].strip()
    entries = [text[m.end():found[i+1].start() if i+1 < len(found) else len(text)].strip() for i,m in enumerate(found)]
    if any(not x for x in entries): raise ValueError('Leere Aufgabe oder Antwort')
    return preface, entries


def parse_case(name, text, modified_date):
    match = re.fullmatch(r'Tag_([123])_Fall_(\d{2})_.+\.pdf', name)
    if not match: raise ValueError(f'Unbekannter Dateiname {name}')
    day, number = map(int, match.groups())
    text = PAGE_HEADER.sub('', text.replace('\r\n', '\n'))
    if not text.startswith(f'FALLBEISPIEL · {DAYS[day]} PRÜFUNGSTAG\n'):
        raise ValueError(f'Prüfungstag stimmt nicht: {name}')
    parts = re.split(r'(?m)^(FALLSITUATION|AUFGABEN|MUSTERLÖSUNGEN|QUELLEN UND EINORDNUNG)\s*$', text)
    if parts[1::2] != ['FALLSITUATION','AUFGABEN','MUSTERLÖSUNGEN','QUELLEN UND EINORDNUNG']:
        raise ValueError(f'Ungültige Abschnitte: {name}')
    intro = parts[0].strip().split('\n', 2)
    if len(intro) != 3: raise ValueError(f'Titel fehlt: {name}')
    title = intro[1].strip()
    instructions, questions = split_numbered(parts[4])
    answer_note, answers = split_numbered(parts[6])
    if len(questions) != len(answers): raise ValueError(f'Ungleiche Aufgaben/Lösungen: {name}')
    source = f'Fallbeispiele/{name}'
    return {'id':f'case-day-{day}-{number:02d}', 'published':modified_date, 'examDay':day, 'title':title,
            'diseases':[title], 'text':intro[2].strip()+'\n\nFALLSITUATION\n'+parts[2].strip(),
            'instructions':instructions, 'answerNote':answer_note,
            'sources':[source,parts[8].strip()],
            'questions':[{'question':q, 'answer':a, 'sources':[source]} for q,a in zip(questions,answers)]}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--directory',type=Path,required=True);p.add_argument('--base',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--modified-date',required=True, help='Beobachtetes Änderungsdatum der Drive-Dateien (JJJJ-MM-TT)')
    args=p.parse_args()
    if args.output.resolve()==args.base.resolve(): p.error('Ausgabe und Basis müssen verschieden sein')
    files=sorted(args.directory.glob('Tag_*_Fall_*.pdf'))
    from pypdf import PdfReader
    cases=[]
    for f in files:
        reader=PdfReader(f,strict=True)
        if reader.is_encrypted: raise ValueError(f'Verschlüsselt: {f.name}')
        cases.append(parse_case(f.name, '\n\n'.join(p.extract_text() or '' for p in reader.pages), args.modified_date))
    if {c['id'] for c in cases}!={f'case-day-{day}-{n:02d}' for day in range(1,4) for n in range(1,11)}:
        raise ValueError('Erwartet: genau 10 unterschiedliche Fälle pro Prüfungstag')
    base=json.loads(args.base.read_text(encoding='utf-8'))
    if base.get('schemaVersion') != 1 or type(base.get('revision')) is not int or any(not isinstance(base.get(k),list) for k in ('notes','cards','cases','quizzes')): raise ValueError('Ungültiger Basiskatalog')
    if base['cases']: raise ValueError('Basis enthält bereits Fälle; Import nicht automatisch überschreiben')
    base['cases']=cases;base['revision']+=1
    data=(json.dumps(base,ensure_ascii=False,indent=2)+'\n').encode()
    if len(data)>MAX_CATALOG: raise ValueError('Katalog zu groß')
    save_atomic(args.output,data)
    print(f"{len(cases)} Fälle und {sum(len(c['questions']) for c in cases)} Aufgaben importiert")

if __name__=='__main__': main()
