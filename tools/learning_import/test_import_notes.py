import json
import unittest
from pathlib import Path
from import_notes import TOPICS, expected_names, index_files, assemble, save_atomic
import tempfile

class ImportTests(unittest.TestCase):
    def setUp(self):
        self.files = [{'id': str(i), 'name': name, 'size': '1000', 'mimeType': 'application/pdf'}
                      for i, name in enumerate(expected_names())]
        self.base = {'schemaVersion': 1, 'revision': 9, 'notes': [], 'cards': [{'id':'existing'}],
                     'cases': [{'id':'case'}], 'quizzes': [{'id':'quiz'}]}

    def test_topics_match_app_and_all_variants_are_imported(self):
        kotlin = (Path(__file__).resolve().parents[2] / 'app/src/main/java/de/pflegeexamen/app/domain/model/DomainModels.kt').read_text()
        positions = [kotlin.index('title = "' + title + '"') for _, title in TOPICS]
        self.assertEqual(positions, sorted(positions))
        self.assertEqual(len(self.files), 74)
        indexed = index_files(self.files)
        result = json.loads(assemble(self.base, indexed, lambda f: f['name'] + 'x' * 100))
        self.assertEqual(result['revision'], 10)
        self.assertEqual(result['cards'], self.base['cards'])
        self.assertEqual(result['cases'], self.base['cases'])
        self.assertEqual(result['quizzes'], self.base['quizzes'])
        self.assertEqual(len(result['notes']), 42)
        first = result['notes'][0]
        self.assertIn('Einfach_', first['short'])
        self.assertIn('_Neu.pdf', first['balanced'])
        self.assertIn('_Neu.pdf', first['detailed'])
        self.assertEqual(first['formatVersion'], 2)

    def test_missing_duplicate_wrong_type_and_unexpected_are_rejected(self):
        for files in [self.files[:-1], self.files + [self.files[0]],
                      [dict(self.files[0], mimeType='text/plain')] + self.files[1:],
                      self.files + [dict(self.files[0], name='Anderes_Neu.pdf')]]:
            with self.subTest(files=files[-1]['name']), self.assertRaises(ValueError):
                index_files(files)

    def test_unreadable_text_fails_without_output(self):
        with self.assertRaises(ValueError):
            assemble(self.base, index_files(self.files), lambda _: '')

    def test_atomic_write(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'private.json'
            save_atomic(path, b'one')
            save_atomic(path, b'two')
            self.assertEqual(path.read_bytes(), b'two')
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)

if __name__ == '__main__':
    unittest.main()
