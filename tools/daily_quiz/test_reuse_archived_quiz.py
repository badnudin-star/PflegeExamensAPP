import copy
import datetime as dt
import json
from pathlib import Path
import unittest
from zoneinfo import ZoneInfo

import reuse_archived_quiz as reuse
import test_quiz_pipeline


class Response:
    def __init__(self, status, data): self.status_code, self.data, self.closed = status, data, False
    def iter_content(self, size):
        for i in range(0, len(self.data), size): yield self.data[i:i+size]
    def close(self): self.closed = True


class Http:
    def __init__(self, response): self.response, self.requests = response, []
    def get(self, url, **kwargs): self.requests.append((url, kwargs)); return self.response


class ReuseQuizTests(unittest.TestCase):
    def setUp(self):
        self.draft = test_quiz_pipeline.QuizTests().draft()
        self.raw = json.dumps(self.draft, ensure_ascii=False).encode()
        self.today = dt.date.fromisoformat(self.draft['date']) + dt.timedelta(days=1)
        self.clock = lambda: dt.datetime.combine(self.today, dt.time(12), ZoneInfo('Europe/Berlin'))
    def test_copy_preserves_everything_except_date_and_ids(self):
        source = copy.deepcopy(self.draft)
        http = Http(Response(200, self.raw))
        copied = reuse.copy_for_today(http, 'https://content.example', source['date'], self.clock)
        self.assertEqual(copied['date'], self.today.isoformat())
        self.assertEqual(self.draft, source)
        for i, (old, new) in enumerate(zip(source['quizzes'], copied['quizzes']), 1):
            self.assertEqual(new['id'], f'quiz-{self.today.isoformat()}-{i:02d}')
            self.assertEqual(new['published'], self.today.isoformat())
            self.assertEqual({k:v for k,v in old.items() if k not in ('id','published')},
                             {k:v for k,v in new.items() if k not in ('id','published')})
        self.assertTrue(http.response.closed)
        self.assertFalse(http.requests[0][1]['allow_redirects'])
        self.assertTrue(http.requests[0][1]['stream'])
    def test_bad_dates_and_late_time_do_not_read_archive(self):
        for day, clock in (('2020-02-30', self.clock), (self.today.isoformat(), self.clock),
                           (self.draft['date'], lambda: self.clock().replace(hour=23,minute=55))):
            http = Http(Response(200,self.raw))
            with self.assertRaises(ValueError): reuse.copy_for_today(http, 'https://content.example', day, clock)
            self.assertEqual(http.requests, [])
    def test_missing_incomplete_mismatched_and_oversize_archives_fail(self):
        incomplete = copy.deepcopy(self.draft); incomplete['quizzes'].pop()
        wrong = copy.deepcopy(self.draft); wrong['date'] = '2020-01-01'
        for status, raw in ((404,b'{}'),(503,b'{}'),(200,json.dumps(incomplete).encode()),
                            (200,json.dumps(wrong).encode()),(200,b'x'*(reuse.MAX_ARCHIVE+1))):
            http = Http(Response(status,raw))
            with self.assertRaises(ValueError): reuse.copy_for_today(http, 'https://content.example', self.draft['date'], self.clock)
            self.assertTrue(http.response.closed)
    def test_workflow_is_manual_confirmed_main_only_and_shares_cas_lock(self):
        text = (Path(__file__).resolve().parents[2] / '.github/workflows/reuse-archived-quiz.yml').read_text()
        for part in ('workflow_dispatch:', 'default: false', "github.ref == 'refs/heads/main'", 'inputs.confirm == true',
                     'group: learning-content-publication', 'reuse_archived_quiz.py'):
            self.assertIn(part,text)
        self.assertNotIn('schedule:',text)
        self.assertNotIn('GEMINI_API_KEY',text)


if __name__ == '__main__': unittest.main()
