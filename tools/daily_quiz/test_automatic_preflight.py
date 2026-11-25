import copy
import datetime as dt
from pathlib import Path
import unittest
from unittest.mock import patch
from zoneinfo import ZoneInfo

import automatic_preflight as preflight
import test_quiz_pipeline
from test_offline_flow import BASE


class Response:
    def __init__(self, status): self.status_code, self.closed = status, False
    def close(self): self.closed = True


class Http:
    def __init__(self, status=404): self.response, self.calls = Response(status), []
    def get(self, *args, **kwargs): self.calls.append((args, kwargs)); return self.response


class AutomaticPreflightTests(unittest.TestCase):
    def setUp(self):
        self.draft = test_quiz_pipeline.QuizTests().draft()
        self.now = dt.datetime.fromisoformat(self.draft['date'] + 'T12:00:00').replace(tzinfo=ZoneInfo('Europe/Berlin'))
        self.empty = copy.deepcopy(BASE)
    def check(self, http, remote):
        with patch.object(preflight.qp, 'read_remote', return_value=remote):
            return preflight.check(http, 'https://content.example', self.now)
    def test_empty_day_and_archive_starts_generation(self):
        http = Http()
        self.assertFalse(self.check(http, self.empty))
        self.assertEqual(len(http.calls), 1)
        self.assertFalse(http.calls[0][1]['allow_redirects'])
        self.assertTrue(http.response.closed)
    def test_existing_full_day_skips_and_partial_blocks(self):
        for size in (1, 49, 50, 51):
            http = Http()
            qs = self.draft['quizzes'][:size]
            if size == 51: qs = qs + [qs[0]]
            catalog = {**self.empty, 'quizzes':qs}
            if size == 50: self.assertTrue(self.check(http, catalog))
            else:
                with self.assertRaises(ValueError): self.check(http, catalog)
            self.assertEqual(http.calls, [])
    def test_missing_server_bad_archive_and_cutoff_block(self):
        with self.assertRaises(ValueError): self.check(Http(), None)
        for status in (200, 403, 500):
            http = Http(status)
            with self.assertRaises(ValueError): self.check(http, self.empty)
            self.assertTrue(http.response.closed)
        with patch.object(preflight.qp, 'read_remote') as remote, self.assertRaises(ValueError):
            preflight.check(Http(), 'https://content.example', self.now.replace(hour=23, minute=55))
        remote.assert_not_called()
    def test_workflow_retry_skips_generation_if_already_published(self):
        workflow = (Path(__file__).resolve().parents[2] / '.github/workflows/daily-quiz-draft.yml').read_text()
        for part in ("cron: '12 4 * * *'", "cron: '12 10 * * *'", 'automatic_preflight.py', "steps.today.outputs.skip != 'true'"):
            self.assertIn(part, workflow)
        self.assertLess(workflow.index('automatic_preflight.py'), workflow.index('quiz_pipeline.py generate'))


if __name__ == '__main__': unittest.main()
