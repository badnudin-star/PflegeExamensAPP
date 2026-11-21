import json
import unittest
from pathlib import Path
from import_notes import TOPICS, expected_names, topic_candidates, topic_files, index_files, assemble, save_atomic
import tempfile

class ImportTests(unittest.TestCase):
    def setUp(self):
        self.files = [{'id': str(i), 'name': name, 'size': '1000', 'mimeType': 'application/pdf'}
                      for i, name in enumerate(sorted({name for n in range(len(TOPICS)) for name in topic_candidates(n)[0]}))]
        self.base = {'schemaVersion': 1, 'revision': 9, 'notes': [], 'cards': [{'id':'existing'}],
                     'cases': [{'id':'case'}], 'quizzes': [{'id':'quiz'}]}

    def test_topics_match_app_and_all_variants_are_imported(self):
        kotlin = (Path(__file__).resolve().parents[2] / 'app/src/main/java/de/pflegeexamen/app/domain/model/DomainModels.kt').read_text()
        positions = [kotlin.index('title = "' + title + '"') for _, title in TOPICS]
        self.assertEqual(positions, sorted(positions))
        self.assertEqual(len(self.files), 84)
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

    def test_individual_pairs_take_priority_and_legacy_combined_pair_is_supported(self):
        indexed = index_files(self.files)
        for slug in ('Leben_im_Familienalltag', 'SGB_V_und_SGB_XI', 'Soziale_Rolle',
                     'Pflegebeduerftigkeit', 'Gesundheitsfoerderung', 'Praevention',
                     'Vorsorge', 'Ethische_Prinzipien_und_Dilemma'):
            i = next(i for i, t in enumerate(TOPICS) if t[0] == slug)
            detailed, simple = topic_files(indexed, i)
            self.assertEqual(detailed, slug + '.pdf')
            self.assertEqual(simple, 'Einfach_' + slug + '.pdf')
        i = next(i for i, t in enumerate(TOPICS) if t[0] == 'Vorsorge')
        dedicated, legacy = topic_candidates(i)
        both = dict(indexed, **{name: {'name': name} for name in legacy})
        self.assertEqual(topic_files(both, i), dedicated)
        both.pop(dedicated[0]); both.pop(dedicated[1])
        self.assertEqual(topic_files(both, i), legacy)
        both[dedicated[0]] = {'name': dedicated[0]}
        with self.assertRaisesRegex(ValueError, 'Unvollständiges'):
            topic_files(both, i)

    def test_dedicated_sources_recorded_correctly_in_result(self):
        result = json.loads(assemble(self.base, index_files(self.files), lambda f: f['name'] + 'x' * 100))
        for i, (slug, _) in enumerate(TOPICS):
            if len(topic_candidates(i)) > 1:
                detailed, simple = topic_candidates(i)[0]
                self.assertEqual(result['notes'][i]['sources'], ['Lernzettel: ' + detailed, 'Lernzettel: ' + simple])
                self.assertTrue(result['notes'][i]['detailed'].startswith(detailed))
                self.assertTrue(result['notes'][i]['short'].startswith(simple))

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
