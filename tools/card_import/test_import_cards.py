import copy
from unittest.mock import patch
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

    def test_drive_snapshot_imports_real_workbooks_and_keeps_existing_ids(self):
        from openpyxl import load_workbook
        sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'learning_import'))
        from sync_practice import import_practice
        original = importer.read_cards(self.paths)
        name = next(iter(self.paths))
        book = load_workbook(self.paths[name])
        book.worksheets[1]['C3'] = 'Korrigierte Antwort'
        book.worksheets[1].append([2, 'Neue Frage?', 'Neue Antwort.', 'Quelle S. 2'])
        book.save(self.paths[name])
        files = [{'name': name} for name in self.paths]
        base = {'cards': original, 'cases': [], 'notes': [], 'quizzes': []}
        with patch('sync_practice.inventory', return_value=files), \
             patch('sync_practice.download', side_effect=lambda drive, f: self.paths[f['name']].read_bytes()):
            result = import_practice(base, object(), 'cards')
        by_id = {card['id']: card for card in result['cards']}
        self.assertEqual(by_id['card-01-001']['back'], 'Korrigierte Antwort')
        self.assertEqual(by_id['card-01-002']['front'], 'Neue Frage?')
        self.assertTrue({c['id'] for c in original}.issubset(by_id))
        self.assertEqual(base['cards'][0]['back'], 'Antwort.')

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
