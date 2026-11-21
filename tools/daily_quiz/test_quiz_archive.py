import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

import quiz_archive as archive
import test_quiz_pipeline


class QuizArchiveTests(unittest.TestCase):
    def catalog(self):
        draft = test_quiz_pipeline.QuizTests().draft()
        questions = draft['quizzes']
        earlier = [dict(q, id='earlier-' + q['id'], published='2026-11-17') for q in questions[:3]]
        return {'schemaVersion': 1, 'revision': 7, 'notes': [], 'cards': [], 'cases': [],
                'quizzes': earlier + questions}

    def raw(self, catalog):
        return (json.dumps(catalog, ensure_ascii=False) + '\n').encode()

    def test_backup_preserves_every_question_and_verifies_against_original(self):
        catalog = self.catalog()
        raw = self.raw(catalog)
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'archive.zip'
            self.assertEqual(archive.create(raw, path), 2)
            self.assertEqual(archive.verify(raw, path), 2)
            self.assertEqual(path.stat().st_mode & 0o077, 0)
            with zipfile.ZipFile(path) as data:
                manifest = json.loads(data.read('manifest.json'))
                self.assertEqual(manifest['catalogSha256'], hashlib.sha256(raw).hexdigest())
                self.assertEqual(manifest['catalogRevision'], 7)
                restored = []
                for entry in manifest['days']:
                    content = data.read(entry['file'])
                    self.assertEqual(hashlib.sha256(content).hexdigest(), entry['sha256'])
                    day = json.loads(content)
                    self.assertEqual(entry['questions'], len(day['quizzes']))
                    restored += day['quizzes']
                self.assertEqual(restored, catalog['quizzes'])
            with self.assertRaises(FileExistsError): archive.create(raw, path)
            self.assertEqual(archive.verify(raw, path), 2)
            with self.assertRaises(ValueError): archive.verify(raw + b' ', path)
            changed = copy.deepcopy(catalog)
            changed['quizzes'][0]['question'] = 'Verändert'
            with self.assertRaises(ValueError): archive.verify(self.raw(changed), path)

    def test_bad_date_or_invalid_catalog_never_create_archive(self):
        catalog = self.catalog()
        for invalid in ('20261118', '2026-02-30', '../date', '2026-11-18\n'):
            with self.subTest(invalid=invalid), tempfile.TemporaryDirectory() as temp:
                catalog['quizzes'][0]['published'] = invalid
                path = Path(temp) / 'new.zip'
                with self.assertRaises(ValueError): archive.create(self.raw(catalog), path)
                self.assertFalse(path.exists())
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'new.zip'
            with self.assertRaises(ValueError): archive.create(b'{}', path)
            self.assertFalse(path.exists())

    def test_corruption_extra_members_duplicate_members_or_missing_data_fail_verification(self):
        raw = self.raw(self.catalog())
        files = archive.expected_files(raw)
        for mode in ('corrupt', 'extra', 'duplicate', 'missing'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temp:
                path = Path(temp) / 'bad.zip'
                with zipfile.ZipFile(path, 'w') as z:
                    for name, data in files.items():
                        if mode == 'missing' and name == 'manifest.json': continue
                        z.writestr(name, data + b'!' if mode == 'corrupt' and name == 'manifest.json' else data)
                    if mode == 'extra': z.writestr('extra', b'x')
                    if mode == 'duplicate':
                        import warnings
                        with warnings.catch_warnings():
                            warnings.simplefilter('ignore', UserWarning)
                            z.writestr('manifest.json', files['manifest.json'])
                with self.assertRaises(ValueError): archive.verify(raw, path)

    def test_empty_quiz_archive_and_unchanged_source(self):
        catalog = self.catalog()
        catalog['quizzes'] = []
        raw = self.raw(catalog)
        with tempfile.TemporaryDirectory() as temp:
            original, path = Path(temp) / 'input.json', Path(temp) / 'archive.zip'
            original.write_bytes(raw)
            self.assertEqual(archive.create(original.read_bytes(), path), 0)
            self.assertEqual(archive.verify(original.read_bytes(), path), 0)
            self.assertEqual(original.read_bytes(), raw)
