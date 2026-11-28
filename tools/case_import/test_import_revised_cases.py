import unittest
from import_revised_cases import parse_revised_case

NAME = 'Tag_1_Fall_01_Test.pdf'
TEXT = '''EXAMENSVORBEREITUNG · FALLÜBUNG · ARBEITSSTAND
1
PRÜFUNGSTAG 1 · FALL 01
Synthetisches Fallbeispiel
Arbeitsstand: Eine fiktive Übung.
FALLSITUATION
01   Eine erfundene Person braucht Hilfe.
02   Sie möchte selbst entscheiden.
EXAMENSVORBEREITUNG · FALLÜBUNG · ARBEITSSTAND
2
AUFGABEN
Bearbeiten Sie die Aufgaben.
01 Definieren Sie den Begriff.
02 Analysieren Sie die
Situation der Person.
MUSTERLÖSUNGEN UND OPERATORENPRÜFUNG
Musterantworten sind Beispiele.
01 · Definieren Sie den Begriff.
Eine allgemeine Definition.
Fundstelle: Quelle 1.
Operatorenprüfung: Definition.
02 · Analysieren Sie die Situation
 der Person.
Eine fallbezogene Antwort.
Fundstelle: Fall, Z. 1–2.
Operatorenprüfung: Analyse.
QUELLEN UND HINWEISE
Fiktive Testquellen, keine Aktualitätsprüfung.
'''


class RevisedCasesTest(unittest.TestCase):
    def test_roundtrip_fields_line_numbers_and_distinct_identity(self):
        case = parse_revised_case(NAME, TEXT, '2026-01-01')
        self.assertEqual(case['examDay'], 1)
        self.assertIn('01   Eine erfundene Person braucht Hilfe.\n\n02   Sie möchte selbst entscheiden.', case['text'])
        self.assertEqual(len(case['questions']), 2)
        self.assertIn('Fundstelle:', case['questions'][1]['answer'])
        self.assertNotIn('Analysieren Sie', case['questions'][1]['answer'])
        self.assertEqual(case['id'], parse_revised_case(NAME, TEXT, '2026-02-02')['id'])
        self.assertNotEqual(case['id'], parse_revised_case(NAME, TEXT.replace('Eine allgemeine Definition.', 'Eine andere Definition.'), '2026-01-01')['id'])

    def test_missing_misaligned_or_incomplete_data_rejected(self):
        changes = [
            TEXT.replace('PRÜFUNGSTAG 1 · FALL 01', 'PRÜFUNGSTAG 2 · FALL 01'),
            TEXT.replace('02   Sie möchte', '03   Sie möchte'),
            TEXT.replace('02 · Analysieren', '03 · Analysieren'),
            TEXT.replace('02 · Analysieren Sie die Situation', '02 · Ändern Sie die Situation'),
            TEXT.replace('Fundstelle: Quelle 1.', 'Keine Fundstelle.'),
            TEXT.replace('QUELLEN UND HINWEISE', 'QUELLEN'),
        ]
        for text in changes:
            with self.subTest(text=text[:60]), self.assertRaises(ValueError):
                parse_revised_case(NAME, text, '2026-01-01')
        with self.assertRaises(ValueError): parse_revised_case('ALTSTAND_' + NAME, TEXT, '2026-01-01')


if __name__ == '__main__': unittest.main()
