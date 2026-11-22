import copy
import hashlib
import json
from pathlib import Path
import tempfile
import subprocess
import sys
import unittest
from unittest.mock import patch
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

    def test_restores_missing_days_into_new_candidate_without_touching_learning_content(self):
        original = self.catalog()
        target = copy.deepcopy(original)
        target['revision'] = 8
        topic = original['quizzes'][0]['topic']
        target['notes'] = [{'topic': topic, 'short': 'Kurz', 'balanced': 'Mittel', 'detailed': 'Ausführlich', 'sources': ['Test']}]
        target['cards'] = [{'id': 'card-1', 'topic': topic, 'front': 'Frage', 'back': 'Antwort', 'sources': ['Test']}]
        target['cases'] = [{'id': 'case-1', 'published': '2026-11-01', 'diseases': ['Test'], 'text': 'Falltext', 'sources': ['Test'],
                            'questions': [{'question': 'Aufgabe', 'answer': 'Antwort', 'sources': ['Test']}]}]
        target['additionalMetadata'] = {'unchanged': ['a', 1]}
        target['quizzes'] = [q for q in target['quizzes'] if q['published'] != '2026-11-17']
        raw, target_raw = self.raw(original), self.raw(target)
        with tempfile.TemporaryDirectory() as temp:
            path, output = Path(temp) / 'archive.zip', Path(temp) / 'candidate.json'
            archive.create(raw, path)
            self.assertEqual(archive.restore(raw, path, target_raw, output), 3)
            candidate = json.loads(output.read_bytes())
            self.assertEqual(candidate['revision'], 9)
            for key in ('schemaVersion', 'notes', 'cards', 'cases', 'additionalMetadata'):
                self.assertEqual(candidate[key], target[key])
            self.assertEqual(candidate['quizzes'], target['quizzes'] + original['quizzes'][:3])
            self.assertEqual(output.stat().st_mode & 0o077, 0)
            self.assertEqual(raw, self.raw(original)); self.assertEqual(target_raw, self.raw(target))
            self.assertEqual(archive.verify(raw, path), 2)
            before = output.read_bytes()
            with self.assertRaises(FileExistsError): archive.restore(raw, path, target_raw, output)
            self.assertEqual(output.read_bytes(), before)
            self.assertEqual(sorted(p.name for p in Path(temp).iterdir()), ['archive.zip', 'candidate.json'])

    def test_existing_same_day_is_matched_by_id_and_exact_question_not_order(self):
        original = self.catalog()
        target = copy.deepcopy(original)
        target['revision'] += 1
        target['quizzes'] = list(reversed(target['quizzes'][3:]))
        with tempfile.TemporaryDirectory() as temp:
            path, output = Path(temp) / 'archive.zip', Path(temp) / 'candidate.json'
            archive.create(self.raw(original), path)
            self.assertEqual(archive.restore(self.raw(original), path, self.raw(target), output), 3)
            result = json.loads(output.read_bytes())
            self.assertEqual(result['quizzes'][:len(target['quizzes'])], target['quizzes'])

    def test_mismatching_dates_ids_stale_revision_or_nothing_to_restore_fail_closed(self):
        original = self.catalog()
        current = copy.deepcopy(original)
        current['revision'] = 8
        targets = []
        targets.append(copy.deepcopy(current))  # nothing missing
        conflict = copy.deepcopy(current); conflict['quizzes'][0]['correctIndex'] = (conflict['quizzes'][0]['correctIndex'] + 1) % 4; targets.append(conflict)
        partial = copy.deepcopy(current); partial['quizzes'].pop(0); targets.append(partial)
        wrong_day = copy.deepcopy(current); wrong_day['quizzes'][0]['published'] = '2026-11-16'; targets.append(wrong_day)
        stale = copy.deepcopy(current); stale['revision'] = 6; targets.append(stale)
        ambiguous_revision = copy.deepcopy(current); ambiguous_revision['revision'] = 7; ambiguous_revision['quizzes'].pop(0); targets.append(ambiguous_revision)
        invalid_day = copy.deepcopy(current); invalid_day['quizzes'][0]['published'] = '2026-02-30'; targets.append(invalid_day)
        for target in targets:
            with self.subTest(target=target['revision']), tempfile.TemporaryDirectory() as temp:
                path, output = Path(temp) / 'archive.zip', Path(temp) / 'candidate.json'
                archive.create(self.raw(original), path)
                with self.assertRaises(ValueError): archive.restore(self.raw(original), path, self.raw(target), output)
                self.assertFalse(output.exists())
                self.assertEqual([p.name for p in Path(temp).iterdir()], ['archive.zip'])

    def test_cross_day_id_collision_rejects_even_when_original_day_is_missing(self):
        original = self.catalog()
        target = copy.deepcopy(original); target['revision'] = 8
        moved = dict(target['quizzes'][0], published='2026-11-16')
        target['quizzes'] = [moved] + target['quizzes'][3:]
        with tempfile.TemporaryDirectory() as temp:
            path, output = Path(temp) / 'archive.zip', Path(temp) / 'candidate.json'
            archive.create(self.raw(original), path)
            with self.assertRaisesRegex(ValueError, 'Frage-ID'):
                archive.restore(self.raw(original), path, self.raw(target), output)
            self.assertFalse(output.exists())

    def test_revision_exhaustion_fails_before_candidate_is_created(self):
        original = self.catalog()
        target = copy.deepcopy(original); target['revision'] = 2**53 - 2; target['quizzes'] = target['quizzes'][3:]
        with tempfile.TemporaryDirectory() as temp:
            path, output = Path(temp) / 'archive.zip', Path(temp) / 'candidate.json'
            archive.create(self.raw(original), path)
            with self.assertRaises(ValueError): archive.restore(self.raw(original), path, self.raw(target), output)
            self.assertFalse(output.exists())

    def test_tampered_backup_duplicate_json_key_and_output_limit_are_rejected(self):
        original = self.catalog()
        target = copy.deepcopy(original); target['revision'] = 8
        target['quizzes'] = [dict(q, id='future-' + q['id'], published='2026-12-01') for q in original['quizzes'][3:]]
        raw, target_raw = self.raw(original), self.raw(target)
        with tempfile.TemporaryDirectory() as temp:
            path, output = Path(temp) / 'archive.zip', Path(temp) / 'candidate.json'
            archive.create(raw, path)
            with self.assertRaises(ValueError): archive.restore(raw + b' ', path, target_raw, output)
            duplicate = target_raw.replace(b'"revision": 8', b'"revision": 8, "revision": 8')
            self.assertNotEqual(duplicate, target_raw)
            with self.assertRaises(ValueError): archive.restore(raw, path, duplicate, output)
            with patch.object(archive, 'MAX_CATALOG', max(len(raw), len(target_raw)) + 10):
                with self.assertRaises(ValueError): archive.restore(raw, path, target_raw, output)
            self.assertFalse(output.exists())

    def test_failed_atomic_link_never_overwrites_or_leaves_candidate(self):
        original = self.catalog()
        target = copy.deepcopy(original); target['revision'] = 8; target['quizzes'] = target['quizzes'][3:]
        raw, target_raw = self.raw(original), self.raw(target)
        with tempfile.TemporaryDirectory() as temp:
            path, output = Path(temp) / 'archive.zip', Path(temp) / 'candidate.json'
            archive.create(raw, path)
            with patch.object(archive.os, 'link', side_effect=OSError('link unsupported')):
                with self.assertRaises(OSError): archive.restore(raw, path, target_raw, output)
            self.assertFalse(output.exists())
            self.assertEqual([p.name for p in Path(temp).iterdir()], ['archive.zip'])

    def test_restore_cli_uses_new_file_and_leaves_all_inputs_unchanged(self):
        original = self.catalog()
        target = copy.deepcopy(original); target['revision'] = 8; target['quizzes'] = target['quizzes'][3:]
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source, current, backup, output = (root / name for name in ('source.json', 'current.json', 'archive.zip', 'candidate.json'))
            source.write_bytes(self.raw(original)); current.write_bytes(self.raw(target))
            archive.create(source.read_bytes(), backup)
            before = {p: p.read_bytes() for p in (source, current, backup)}
            result = subprocess.run([sys.executable, str(Path(archive.__file__)), 'restore', str(source), str(backup),
                                     '--target', str(current), '--output', str(output)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('Nicht veröffentlicht', result.stdout)
            self.assertEqual(json.loads(output.read_bytes())['revision'], 9)
            self.assertTrue(all(p.read_bytes() == content for p, content in before.items()))
            # Never replace any input, including the ZIP or either JSON.
            for existing in (source, current, backup):
                with self.assertRaises(FileExistsError): archive.restore(before[source], backup, before[current], existing)
            self.assertTrue(all(p.read_bytes() == content for p, content in before.items()))

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

    def test_ambiguous_or_nonfinite_source_never_creates_or_verifies_backup(self):
        raw = self.raw(self.catalog())
        duplicate = raw.replace(b'"revision": 7', b'"revision": 7, "revision": 7')
        nonfinite = raw.replace(b'"revision": 7', b'"extra": NaN, "revision": 7')
        overflow = raw.replace(b'"revision": 7', b'"extra": 1e999, "revision": 7')
        self.assertNotEqual(raw, duplicate)
        self.assertNotEqual(raw, nonfinite)
        nested = raw.replace(b'"correctIndex": 0', b'"correctIndex": 0, "correctIndex": 0', 1)
        negative_overflow = raw.replace(b'"revision": 7', b'"extra": -1e999, "revision": 7')
        self.assertNotEqual(raw, overflow)
        self.assertNotEqual(raw, nested)
        self.assertNotEqual(raw, negative_overflow)
        with tempfile.TemporaryDirectory() as temp:
            good, bad = Path(temp) / 'good.zip', Path(temp) / 'bad.zip'
            archive.create(raw, good)
            for invalid in (duplicate, nested, nonfinite, overflow, negative_overflow):
                with self.subTest(invalid=invalid[:120]):
                    with self.assertRaises(ValueError): archive.create(invalid, bad)
                    self.assertFalse(bad.exists())
                    with self.assertRaises(ValueError): archive.verify(invalid, good)
