import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import unittest

import probe_gemini as probe
import quiz_pipeline

ROOT = Path(__file__).resolve().parent


class Response:
    def __init__(self, status=200, content=None):
        self.status_code = status
        self.content = content if content is not None else json.dumps({'candidates': [{'content': {'parts': [{'text': 'test'}]}}]}).encode()
        self.closed = False
    def iter_content(self, chunk_size):
        assert chunk_size == 4096
        yield self.content
    def close(self):
        self.closed = True


class Http:
    def __init__(self, responses):
        self.responses = iter(responses)
        self.calls = []
    def post(self, url, **kwargs):
        self.calls.append((url, kwargs))
        value = next(self.responses)
        if isinstance(value, Exception):
            raise value
        return value


class GeminiProbeTests(unittest.TestCase):
    def test_two_synthetic_requests_same_model_schema_no_retry_or_redirect(self):
        responses = [Response(), Response()]
        http = Http(responses)
        self.assertEqual(probe.probe(http, 'private-key', 'test-model'),
                         ('HTTP 200, Antworttext vorhanden', 'HTTP 200, Antworttext vorhanden'))
        self.assertEqual(len(http.calls), 2)
        for url, kwargs in http.calls:
            self.assertEqual(url, 'https://generativelanguage.googleapis.com/v1beta/models/test-model:generateContent')
            self.assertFalse(kwargs['allow_redirects'])
            self.assertTrue(kwargs['stream'])
            self.assertEqual(kwargs['timeout'], probe.TIMEOUT_SECONDS)
            self.assertNotIn('private-key', url)
            self.assertEqual(kwargs['headers']['x-goog-api-key'], 'private-key')
            self.assertTrue(responses[0].closed and responses[1].closed)
        self.assertEqual(http.calls[1][1]['json']['generationConfig']['responseSchema'], quiz_pipeline.SCHEMA)
        self.assertNotIn('source', json.dumps(http.calls[0][1]['json']).lower())

    def test_status_network_errors_and_large_or_invalid_body_are_not_leaked(self):
        for response, expected in [
            (Response(503, b'private response'), 'HTTP 503'),
            (Response(429, b'private response'), 'HTTP 429'),
            (Response(200, b'x' * (probe.MAX_RESPONSE_BYTES + 1)), 'HTTP 200, Antwort zu groß'),
            (Response(200, b'private response'), 'Verbindung oder Antwort nicht auswertbar'),
            (RuntimeError('private exception'), 'Verbindung oder Antwort nicht auswertbar'),
        ]:
            with self.subTest(expected=expected):
                http = Http([response])
                log = io.StringIO()
                with contextlib.redirect_stdout(log), contextlib.redirect_stderr(log):
                    self.assertEqual(probe.request_status(http, 'private-key', 'test-model', {}), expected)
                self.assertEqual(len(http.calls), 1)
                self.assertNotIn('private', log.getvalue())
                if isinstance(response, Response):
                    self.assertTrue(response.closed)

    def test_invalid_configuration_never_contacts_network(self):
        for key, model in [('', 'model'), ('private', ''), ('private', '../foo'), ('private', 'model?key=private')]:
            http = Http([])
            self.assertIsNone(probe.probe(http, key, model))
            self.assertEqual(http.calls, [])

    def test_confirmation_is_required_before_requests_are_imported_or_sent(self):
        env = {k: v for k, v in os.environ.items() if k not in ('CONFIRMED', 'GEMINI_API_KEY', 'GEMINI_MODEL')}
        env.update(CONFIRMED='false', GEMINI_API_KEY='private-key', GEMINI_MODEL='test-model')
        result = subprocess.run([sys.executable, str(ROOT / 'probe_gemini.py')], env=env, capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 1)
        self.assertIn('keine Anfrage gesendet', result.stdout)
        self.assertNotIn('private-key', result.stdout + result.stderr)

    def test_workflow_has_no_drive_or_publisher_secrets_and_requires_confirmation(self):
        workflow = (ROOT.parents[1] / '.github' / 'workflows' / 'gemini-synthetic-probe.yml').read_text()
        self.assertIn('workflow_dispatch:', workflow)
        self.assertIn('default: false', workflow)
        self.assertIn('refs/heads/main', workflow)
        self.assertIn('probe_gemini.py', workflow)
        for banned in ('schedule:', 'LEARNING_DRIVE_CREDENTIALS', 'CONTENT_PUBLISH_TOKEN', 'quiz_pipeline.py generate'):
            self.assertNotIn(banned, workflow)


if __name__ == '__main__':
    unittest.main()
