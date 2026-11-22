"""End-to-end simulation only: no Drive, Gemini, Cloudflare or clinical validation."""
import copy
import datetime as dt
import hashlib
import json
import unittest
from unittest.mock import patch

import quiz_pipeline as qp
import review_sheet

DATE = dt.date(2026, 11, 13)
EVIDENCE = 'Dieser längere wörtliche Testbeleg stammt ausschließlich aus der simulierten Quelle.'
SOURCE = EVIDENCE + ' Weiterer künstlicher Text ohne medizinischen Inhalt.'
BASE = {'schemaVersion': 1, 'revision': 3, 'notes': [], 'cards': [], 'cases': [], 'quizzes': []}


class Response:
    def __init__(self, status=200, body=None):
        self.status_code = status
        self.body = body
    def json(self):
        return self.body


class FakeHttp:
    def __init__(self):
        self.requests = []
        self.puts = []
    def post(self, url, **kwargs):
        self.requests.append((url, kwargs))
        group = len(self.requests)
        questions = [dict(question=f'Testfrage {group}-{i}', options=['A', 'B', 'C', 'D'],
                          correctIndex=i % 4, explanation='Test-Erklärung', evidenceId=1)
                     for i in range(10)]
        return Response(body={'candidates': [{'content': {'parts': [{'text': json.dumps(questions)}]}}]})
    def put(self, url, **kwargs):
        self.puts.append((url, kwargs))
        return Response()


class OfflineFlowTests(unittest.TestCase):
    def setUp(self):
        # Synthetic catalog sizes must never be written to a real Actions summary.
        env = patch.dict('os.environ', {'GITHUB_STEP_SUMMARY': ''})
        env.start()
        self.addCleanup(env.stop)

    def index(self):
        from import_notes import topic_candidates
        return {name: {'name': name} for topic in qp.select_topics(DATE)
                for name in topic_candidates(qp.TOPICS.index(topic))[0]}

    def test_full_simulated_generation_review_and_publication(self):
        http = FakeHttp()
        draft = qp.create_draft(self.index(), lambda _: SOURCE,
                                lambda topic, source: qp.gemini_request(http, 'fake-key', 'fake-model', topic, source), DATE)
        self.assertEqual(len(http.requests), 5)
        for url, request in http.requests:
            self.assertEqual(url, 'https://generativelanguage.googleapis.com/v1beta/models/fake-model:generateContent')
            self.assertEqual(request['headers']['x-goog-api-key'], 'fake-key')
            self.assertFalse(request['allow_redirects'])
            self.assertIn(SOURCE, request['json']['contents'][0]['parts'][0]['text'])
        raw = (json.dumps(draft, ensure_ascii=False, indent=2) + '\n').encode()
        sheet = review_sheet.render(raw)
        self.assertIn(hashlib.sha256(raw).hexdigest(), sheet)
        self.assertEqual(sheet.count('class="question"'), 50)
        with patch.object(qp, 'read_remote', return_value=copy.deepcopy(BASE)):
            qp.publish(http, 'https://content.example', 'x' * 32, json.loads(raw), BASE)
        self.assertEqual(len(http.puts), 1)
        _, request = http.puts[0]
        self.assertEqual(request['headers']['If-Match'], '3')
        published = json.loads(request['data'])
        self.assertEqual(published['revision'], 4)
        self.assertEqual(published['quizzes'], draft['quizzes'])
        self.assertEqual(published['cards'], BASE['cards'])
        self.assertEqual(published['cases'], BASE['cases'])

    def test_api_error_and_malformed_response_do_not_produce_draft(self):
        class Unavailable(FakeHttp):
            def post(self, *args, **kwargs):
                return Response(status=429)
        class Malformed(FakeHttp):
            def post(self, *args, **kwargs):
                return Response(body={'candidates': [{'content': {'parts': [{'text': 'not json'}]}}]})
        for client in (Unavailable(), Malformed()):
            with self.assertRaises(ValueError) as err:
                qp.create_draft(self.index(), lambda _: SOURCE,
                                lambda topic, source: qp.gemini_request(client, 'sensitive-fake-key', 'fake-model', topic, source), DATE)
            self.assertNotIn('sensitive-fake-key', str(err.exception))
            self.assertEqual(client.puts, [])

    def test_model_name_is_not_an_arbitrary_url(self):
        http = FakeHttp()
        with self.assertRaises(ValueError):
            qp.gemini_request(http, 'fake-key', '../elsewhere', 'Thema', SOURCE)
        self.assertEqual(http.requests, [])
