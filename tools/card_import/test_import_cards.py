import copy
import sys
import tempfile
import unittest
from pathlib import Path

from openpyxl import Workbook

sys.path.insert(0, str(Path(__file__).resolve().parent))
import import_cards as importer


class CardImportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.paths = {}
        for name, numbers in importer.FILES.items():
            workbook = Workbook()
            workbook.active.title = 'Übersicht'
            for number in sorted(numbers):
                sheet = workbook.create_sheet(f'{number:02d}_Thema')
                sheet.append([f'{number:02d}  {importer.HEADINGS[number - 1]}'])
                sheet.append(['Nr.', 'Frage', 'Antwort', 'Fundstelle'])
                sheet.append([1, 'Frage?', 'Antwort.', 'Quelle S. 1'])
            self.paths[name] = Path(self.temp.name) / name
            workbook.save(self.paths[name])

    def test_all_topics_mapping_and_sources(self):
        cards = importer.read_cards(self.paths)
        self.assertEqual(len(cards), 42)
        self.assertEqual([c['topic'] for c in cards], [t[1] for t in importer.TOPICS])
        self.assertEqual(cards[0]['id'], 'card-01-001')
        self.assertIn('Quelle S. 1', cards[0]['sources'])

    def test_missing_and_wrong_heading_are_rejected(self):
        from openpyxl import load_workbook
        name = next(iter(self.paths))
        w = load_workbook(self.paths[name])
        w.worksheets[1]['A1'] = '01  Falsches Thema'
        w.save(self.paths[name])
        with self.assertRaises(ValueError): importer.read_cards(self.paths)
        with self.assertRaises(ValueError): importer.read_cards({name: self.paths[name]})

    def test_duplicate_number_and_missing_source_are_rejected(self):
        from openpyxl import load_workbook
        name = next(iter(self.paths))
        w = load_workbook(self.paths[name])
        w.worksheets[1].append([1, 'Frage?', 'Antwort.', 'Quelle'])
        w.save(self.paths[name])
        with self.assertRaises(ValueError): importer.read_cards(self.paths)
        w.worksheets[1].delete_rows(4)
        w.worksheets[1]['D3'] = None
        w.save(self.paths[name])
        with self.assertRaises(ValueError): importer.read_cards(self.paths)

if __name__ == '__main__': unittest.main()
