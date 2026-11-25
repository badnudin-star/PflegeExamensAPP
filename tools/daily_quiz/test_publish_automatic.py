import copy
import datetime as dt
import hashlib
import json
from pathlib import Path
import unittest
from unittest.mock import patch
from zoneinfo import ZoneInfo

import publish_automatic as auto
import test_quiz_pipeline
from test_offline_flow import BASE

ROOT = Path(__file__).resolve().parents[2]


class Response:
    def __init__(self, status): self.status_code = status
    def close(self): pass


class Http:
    def __init__(self, archive=404, status=200, error=False):
        self.archive, self.status, self.error = archive, status, error
        self.puts = []
    def get(self, *args, **kwargs):
        self.archive_args = (args, kwargs)
        return Response(self.archive)
    def put(self, *args, **kwargs):
        self.puts.append((args, kwargs))
        if self.error: raise OSError('synthetic timeout')
        return Response(self.status)


class AutomaticPublicationTests(unittest.TestCase):
    def setUp(self):
        self.env = patch.dict('os.environ', {'GITHUB_STEP_SUMMARY': ''})
        self.env.start(); self.addCleanup(self.env.stop)
        self.draft = test_quiz_pipeline.QuizTests().draft()
        self.raw = json.dumps(self.draft, ensure_ascii=False).encode()
        self.digest = hashlib.sha256(self.raw).hexdigest()
        self.clock = lambda: dt.datetime.fromisoformat(self.draft['date'] + 'T12:00:00').replace(tzinfo=ZoneInfo('Europe/Berlin'))
        self.base = copy.deepcopy(BASE)
        self.published = copy.deepcopy(BASE)
        self.published['revision'] += 1
        self.published['quizzes'] = copy.deepcopy(self.draft['quizzes'])

    def run_publish(self, http, remote=None, after=None, clock=None):
        with patch.object(auto.qp, 'read_remote', side_effect=[self.base if remote is None else remote,
                                                               self.published if after is None else after]):
            return auto.publish(http, 'https://content.example', 'x'*32, self.draft, self.base, clock or self.clock)

    def test_prepare_rejects_hash_date_and_midnight(self):
        self.assertEqual(auto.prepare(self.raw, self.digest, self.clock), self.draft)
        with self.assertRaises(ValueError): auto.prepare(self.raw, '0'*64, self.clock)
        tomorrow = lambda: self.clock() + dt.timedelta(days=1)
        with self.assertRaises(ValueError): auto.prepare(self.raw, self.digest, tomorrow)
        late = lambda: self.clock().replace(hour=23, minute=55)
        with self.assertRaises(ValueError): auto.prepare(self.raw, self.digest, late)

    def test_publishes_unmodified_and_verifies(self):
        http = Http()
        self.assertEqual(self.run_publish(http), 'published')
        request = http.puts[0][1]
        sent = json.loads(request['data'])
        self.assertEqual(sent['quizzes'], self.draft['quizzes'])
        self.assertEqual(request['headers']['If-Match'], '3')
        self.assertEqual(sent['notes'], self.base['notes'])
        self.assertFalse(request['allow_redirects'])
        self.assertFalse(http.archive_args[1]['allow_redirects'])

    def test_existing_or_archived_day_never_overwritten(self):
        other = copy.deepcopy(self.published)
        other['quizzes'][0]['question'] += ' anders'
        for remote in (other, self.published):
            http = Http()
            with patch.object(auto.qp, 'read_remote', return_value=remote):
                if remote == other:
                    with self.assertRaises(ValueError): auto.publish(http, 'https://content.example', 'x'*32, self.draft, self.base, self.clock)
                else:
                    self.assertEqual(auto.publish(http, 'https://content.example', 'x'*32, self.draft, self.base, self.clock), 'already-published')
            self.assertEqual(http.puts, [])
        for code in (200, 403, 500):
            http = Http(archive=code)
            with self.assertRaises(ValueError): self.run_publish(http)
            self.assertEqual(http.puts, [])

    def test_missing_catalog_and_bad_clock_never_write(self):
        for remote in (None, {**self.base, 'revision': 2}):
            http = Http()
            with patch.object(auto.qp, 'read_remote', return_value=remote), self.assertRaises(ValueError):
                auto.publish(http, 'https://content.example', 'x'*32, self.draft, self.base, self.clock)
            self.assertEqual(http.puts, [])
        http = Http()
        with self.assertRaises(ValueError):
            self.run_publish(http, clock=lambda: self.clock() + dt.timedelta(days=1))
        self.assertEqual(http.puts, [])

    def test_checks_clock_again_after_archive_lookup(self):
        times = iter([self.clock(), self.clock().replace(hour=23, minute=55)])
        http = Http()
        with self.assertRaises(ValueError): self.run_publish(http, clock=lambda: next(times))
        self.assertEqual(http.puts, [])

    def test_uncertain_write_never_retries_and_requires_exact_readback(self):
        for http in (Http(error=True), Http(status=409)):
            self.assertEqual(self.run_publish(http), 'confirmed-after-uncertain-response')
            self.assertEqual(len(http.puts), 1)
        http = Http(error=True)
        with self.assertRaises(ValueError): self.run_publish(http, after=self.base)
        self.assertEqual(len(http.puts), 1)

    def test_readback_failure_leaves_unknown_and_never_retries(self):
        http = Http()
        with patch.object(auto.qp, 'read_remote', side_effect=[self.base, OSError('synthetic')]), self.assertRaisesRegex(ValueError, 'ungeklärt'):
            auto.publish(http, 'https://content.example', 'x'*32, self.draft, self.base, self.clock)
        self.assertEqual(len(http.puts), 1)

    def test_workflow_guards_and_shared_serialization(self):
        text = (ROOT / '.github/workflows/daily-quiz-draft.yml').read_text()
        for part in ('publish_today:', 'default: false', 'github.event_name == \'schedule\'',
                     "github.ref == 'refs/heads/main'", 'needs.draft.outputs.ready',
                     'group: learning-content-publication', 'needs.draft.outputs.sha256',
                     'actions/download-artifact@v8', 'publish_automatic.py'):
            self.assertIn(part, text)
        self.assertNotIn('review_confirmed', text)


if __name__ == '__main__': unittest.main()
