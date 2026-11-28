"""Parse the line-numbered case exercises without publishing or changing PDFs."""
import hashlib
import json
import re

PAGE_HEADER = re.compile(r'(?m)^EXAMENSVORBEREITUNG · FALLÜBUNG · ARBEITSSTAND[ \t]*\n\d+[ \t]*\n')
QUESTION = re.compile(r'(?m)^(\d{2})[ \t]+(?!·)(?=\S)')
ANSWER = re.compile(r'(?m)^(\d{2})[ \t]+·[ \t]+')
CASE_LINE = re.compile(r'(?m)^(\d{2})[ \t]{2,}(?=\S)')
SECTIONS = ['FALLSITUATION', 'AUFGABEN', 'MUSTERLÖSUNGEN UND OPERATORENPRÜFUNG', 'QUELLEN UND HINWEISE']


def split_entries(text, pattern):
    matches = list(pattern.finditer(text))
    if not matches or [int(m.group(1)) for m in matches] != list(range(1, len(matches) + 1)):
        raise ValueError('Ungültige oder fehlende Aufgabennummern')
    prefix = text[:matches[0].start()].strip()
    entries = [text[m.end():matches[i + 1].start() if i + 1 < len(matches) else len(text)].strip()
               for i, m in enumerate(matches)]
    if any(not value for value in entries):
        raise ValueError('Leere Aufgabe oder Lösung')
    return prefix, entries


def parse_revised_case(name, raw, modified_date):
    match = re.fullmatch(r'Tag_([123])_Fall_(\d{2})_[A-Za-z0-9_]+\.pdf', name)
    if not match:
        raise ValueError('Unbekannter Fall-Dateiname')
    day, number = map(int, match.groups())
    if not 1 <= number <= 10:
        raise ValueError('Unbekannte Fallnummer')
    text = PAGE_HEADER.sub('', raw.replace('\r\n', '\n'))
    parts = re.split(r'(?m)^(FALLSITUATION|AUFGABEN|MUSTERLÖSUNGEN UND OPERATORENPRÜFUNG|QUELLEN UND HINWEISE)[ \t]*$', text)
    if parts[1::2] != SECTIONS:
        raise ValueError('Ungültige Abschnitte im neuen Fall')
    intro = parts[0].strip()
    expected = f'PRÜFUNGSTAG {day} · FALL {number:02d}\n'
    if not intro.startswith(expected) or '\nArbeitsstand:' not in intro:
        raise ValueError('Prüfungstag, Fallnummer oder Arbeitsstand fehlt')
    title, description = intro[len(expected):].split('\nArbeitsstand:', 1)
    title = ' '.join(title.split())
    case_text = parts[2].strip()
    line_numbers = [int(m.group(1)) for m in CASE_LINE.finditer(case_text)]
    if not title or not description.strip() or line_numbers != list(range(1, len(line_numbers) + 1)) or not line_numbers:
        raise ValueError('Titel oder Zeilennummern ungültig')
    instructions, questions = split_entries(parts[4], QUESTION)
    answer_note, answers = split_entries(parts[6], ANSWER)
    if len(questions) != len(answers) or not instructions or not answer_note or not parts[8].strip():
        raise ValueError('Aufgaben und Lösungen sind unvollständig')
    parsed_answers = []
    for question, block in zip(questions, answers):
        # The solution repeats its question; match across line wraps before
        # removing it. A reordered or truncated PDF must never silently pair.
        pattern = r'\s*' + r'\s+'.join(re.escape(word) for word in question.split()) + r'\s+'
        found = re.match(pattern, block)
        if not found:
            raise ValueError('Aufgabe und Lösung stimmen nicht überein')
        answer = block[found.end():].strip()
        if not answer or 'Fundstelle:' not in answer or 'Operatorenprüfung:' not in answer:
            raise ValueError('Lösung oder Fundstelle fehlt')
        parsed_answers.append(answer)
    source = f'Fallbeispiele/Fallbeispiele mit Zeilennummern – Arbeitsstand/{name}'
    body = 'Arbeitsstand: ' + description.strip() + '\n\nFALLSITUATION\n' + case_text
    # The ID changes when content changes, so an old completed flag cannot
    # transfer to a different case; dates and filenames do not affect identity.
    identity = [day, title, body, instructions, answer_note, list(zip(questions, parsed_answers))]
    digest = hashlib.sha256(json.dumps(identity, ensure_ascii=False, separators=(',', ':')).encode('utf-8')).hexdigest()
    return {'id': 'case-v2-' + digest, 'published': modified_date, 'examDay': day, 'title': title,
            'diseases': [title], 'text': body, 'instructions': instructions, 'answerNote': answer_note,
            'sources': [source, parts[8].strip()],
            'questions': [{'question': q, 'answer': a, 'sources': [source]} for q, a in zip(questions, parsed_answers)]}
