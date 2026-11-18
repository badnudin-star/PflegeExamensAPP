import sys
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
from import_cases import parse_case

SAMPLE='''PRÜFUNGSVORBEREITUNG · FALLÜBUNG
1
FALLBEISPIEL · ERSTER PRÜFUNGSTAG
Titel zum Fall
Fiktive Übung.
FALLSITUATION
Person und Situation.
AUFGABEN
Hinweise.
1 · Frage eins?
2 · Frage zwei?
PRÜFUNGSVORBEREITUNG · FALLÜBUNG
2
MUSTERLÖSUNGEN
Antworten sind Beispiele.
1 · Lösung eins.
2 · Lösung zwei.
QUELLEN UND EINORDNUNG
Quellenangabe.
'''
class CaseTests(unittest.TestCase):
    def test_match_by_number_preserves_context(self):
        c=parse_case('Tag_1_Fall_01_Test.pdf',SAMPLE,'2026-11-18')
        self.assertEqual(c['examDay'],1);self.assertEqual(c['title'],'Titel zum Fall')
        self.assertIn('Fiktive Übung',c['text']);self.assertEqual(c['instructions'],'Hinweise.')
        self.assertEqual(c['questions'][1]['question'],'Frage zwei?')
        self.assertEqual(c['questions'][1]['answer'],'Lösung zwei.')
        self.assertIn('Quellenangabe.',c['sources'])
    def test_mismatch_and_wrong_day_fail(self):
        with self.assertRaises(ValueError): parse_case('Tag_2_Fall_01_Test.pdf',SAMPLE,'2026-11-18')
        with self.assertRaises(ValueError): parse_case('Tag_1_Fall_01_Test.pdf',SAMPLE.replace('2 · Lösung zwei.', '3 · Lösung zwei.'),'2026-11-18')
if __name__=='__main__': unittest.main()
