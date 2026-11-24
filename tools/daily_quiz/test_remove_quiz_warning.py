import copy
import hashlib
import json
from pathlib import Path
import unittest
from unittest.mock import patch

import publish_unreviewed
import remove_quiz_warning as tool
import test_quiz_pipeline
from test_offline_flow import BASE

ROOT = Path(__file__).resolve().parents[2]


class Response:
    def __init__(self, status, data=b''):
        self.status_code = status
        self.data = data
        self.closed = False
    def iter_content(self, size):
        yield self.data
    def close(self):
        self.closed = True


class FakeHttp:
    def __init__(self, catalog, archive_codes=(404, 404), put_status=200, write=True):
        self.catalog = copy.deepcopy(catalog)
        self.archive_codes = iter(archive_codes)
        self.put_status = put_status
        self.write = write
        self.gets, self.puts = [], []
    def get(self, url, **kwargs):
        self.gets.append((url, kwargs))
        if '/quiz-archive/' in url:
            return Response(next(self.archive_codes))
        return Response(200, json.dumps(self.catalog).encode())
    def put(self, url, **kwargs):
        self.puts.append((url, kwargs))
        if self.put_status == 200 and self.write:
            self.catalog = json.loads(kwargs['data'])
        return Response(self.put_status)


class RemoveQuizWarningTests(unittest.TestCase):
    def setUp(self):
        self.draft = test_quiz_pipeline.QuizTests().draft()
        self.raw = json.dumps(self.draft, ensure_ascii=False).encode()
        self.digest = hashlib.sha256(self.raw).hexdigest()
        self.marked = publish_unreviewed.prepare(self.raw, self.digest, 'true')
        self.remote = {**copy.deepcopy(BASE), 'quizzes': self.marked['quizzes']}
        patcher = patch.dict('os.environ', {'GITHUB_STEP_SUMMARY': ''})
        patcher.start()
        self.addCleanup(patcher.stop)

    def run_remove(self, http, confirmed='true', raw=None, digest=None, token='x' * 32):
        return tool.remove(http, 'https://content.example', token,
                           self.raw if raw is None else raw, self.digest if digest is None else digest, confirmed)

    def test_remove_restores_only_exact_original_questions_and_cas(self):
        http = FakeHttp(self.remote)
        count, revision = self.run_remove(http)
        self.assertEqual((count, revision), (50, 4))
        self.assertEqual(http.catalog['quizzes'], self.draft['quizzes'])
        for key in ('notes', 'cards', 'cases'):
            self.assertEqual(http.catalog[key], self.remote[key])
        self.assertEqual(len(http.puts), 1)
        self.assertEqual(http.puts[0][1]['headers']['If-Match'], '3')
        self.assertFalse(http.puts[0][1]['allow_redirects'])
        self.assertEqual(len(http.gets), 4)
        self.assertTrue(all(not kwargs['allow_redirects'] for _, kwargs in http.gets))

    def test_rejects_without_sending_anything(self):
        for kwargs in ({'confirmed': 'false'}, {'digest': '0' * 64}, {'token': ''}):
            http = FakeHttp(self.remote)
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                self.run_remove(http, **kwargs)
            self.assertEqual(http.gets, [])
            self.assertEqual(http.puts, [])

    def test_changed_or_missing_questions_abort_without_write(self):
        for change in ('alter', 'missing', 'extra', 'already_unmarked'):
            remote = copy.deepcopy(self.remote)
            if change == 'alter': remote['quizzes'][0]['explanation'] += ' changed'
            if change == 'missing': remote['quizzes'].pop()
            if change == 'extra':
                q = copy.deepcopy(remote['quizzes'][0]); q['id'] = 'extra'; remote['quizzes'].append(q)
            if change == 'already_unmarked': remote['quizzes'][0]['question'] = self.draft['quizzes'][0]['question']
            http = FakeHttp(remote)
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.run_remove(http)
            self.assertEqual(http.puts, [])

    def test_archive_and_conflicts_never_trigger_repeated_writes(self):
        for archive in (200, 403, 503):
            http = FakeHttp(self.remote, archive_codes=(archive,))
            with self.subTest(archive=archive), self.assertRaises(ValueError): self.run_remove(http)
            self.assertEqual(http.puts, [])
        http = FakeHttp(self.remote, put_status=409)
        with self.assertRaises(ValueError): self.run_remove(http)
        self.assertEqual(len(http.puts), 1)
        http = FakeHttp(self.remote, write=False)
        with self.assertRaisesRegex(RuntimeError, 'nicht eindeutig'): self.run_remove(http)
        self.assertEqual(len(http.puts), 1)
        http = FakeHttp(self.remote, archive_codes=(404, 200))
        with self.assertRaisesRegex(RuntimeError, 'nicht eindeutig'): self.run_remove(http)
        self.assertEqual(len(http.puts), 1)

    def test_workflow_requires_explicit_confirmation_and_provenance(self):
        text = (ROOT / '.github/workflows/remove-quiz-warning.yml').read_text()
        self.assertIn('default: false', text)
        self.assertIn('refs/heads/main', text)
        self.assertIn('möglicher Verlust des Übungsfortschritts', text)
        self.assertIn('learning-content-publication', text)
        self.assertNotIn('schedule:', text)
        self.assertNotIn('GEMINI_API_KEY', text)
        self.assertLess(text.index('Bestätigung und Entwurfsherkunft'), text.index('gh run download'))
        self.assertLess(text.index('gh run download'), text.index('remove_quiz_warning.py'))


if __name__ == '__main__':
    unittest.main()
