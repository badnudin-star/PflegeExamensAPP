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

    def test_realistic_profile_reuses_production_prompt_on_only_artificial_sources(self):
        http = Http([Response(503), Response(503)])
        self.assertEqual(probe.realistic_probe(http, 'private-key', 'test-model'), ('HTTP 503', 'HTTP 503'))
        self.assertEqual(len(http.calls), 2)
        for call, length in zip(http.calls, (5000, 12000)):
            body = call[1]['json']
            source = probe.synthetic_source(length)
            self.assertGreaterEqual(len(source), length)
            self.assertLess(len(source), length + 200)
            self.assertEqual(body, quiz_pipeline.gemini_body('Fiktive Farbenwelt', source))
            self.assertIn('exakt 10', body['contents'][0]['parts'][0]['text'])
            self.assertFalse(call[1]['allow_redirects'])

    def test_sizing_profile_uses_two_shorter_synthetic_sources(self):
        http = Http([Response(503), Response(503)])
        self.assertEqual(probe.sized_probe(http, 'private-key', 'model', (1000, 2000)), ('HTTP 503', 'HTTP 503'))
        self.assertEqual(len(http.calls), 2)
        for call, length in zip(http.calls, (1000, 2000)):
            self.assertEqual(call[1]['json'], quiz_pipeline.gemini_body('Fiktive Farbenwelt', probe.synthetic_source(length)))
        self.assertIsNone(probe.sized_probe(Http([]), '', 'model', (1000, 2000)))

    def test_comparison_changes_one_dimension_without_real_sources(self):
        http = Http([Response(503), Response(503)])
        self.assertEqual(probe.comparison_probe(http, 'private-key', 'model'), ('HTTP 503', 'HTTP 503'))
        small, long = [call[1]['json'] for call in http.calls]
        self.assertEqual(small, quiz_pipeline.gemini_body('Fiktive Farbenwelt', probe.synthetic_source(200)))
        expected = quiz_pipeline.gemini_body('Fiktive Farbenwelt', probe.synthetic_source(5000))
        text = expected['contents'][0]['parts'][0]['text']
        expected['contents'][0]['parts'][0]['text'] = text.replace('exakt 10 unterschiedliche', 'exakt 1 unterschiedliche', 1)
        self.assertEqual(long, expected)
        self.assertEqual(probe.comparison_probe(Http([]), '', 'model'), None)

    def test_json_mode_compares_same_prompt_and_schema_only(self):
        http = Http([Response(503), Response(503)])
        self.assertEqual(probe.json_mode_probe(http, 'private-key', 'model'), ('HTTP 503', 'HTTP 503'))
        with_schema, without_schema = [call[1]['json'] for call in http.calls]
        self.assertEqual(with_schema['contents'], without_schema['contents'])
        self.assertEqual(with_schema['generationConfig']['responseSchema'], quiz_pipeline.SCHEMA)
        self.assertEqual(without_schema['generationConfig'], {'responseMimeType': 'application/json'})
        self.assertIsNone(probe.json_mode_probe(Http([]), '', 'model'))

    def test_plain_json_probe_only_uses_artificial_source(self):
        http = Http([Response(503), Response(503)])
        self.assertEqual(probe.plain_json_probe(http, 'private-key', 'model'), ('HTTP 503', 'HTTP 503'))
        structured, plain = [call[1]['json'] for call in http.calls]
        self.assertIn('responseSchema', structured['generationConfig'])
        self.assertNotIn('generationConfig', plain)
        text = plain['contents'][0]['parts'][0]['text']
        self.assertIn('Ausschließlich'.lower(), text.lower())
        self.assertIn('Fiktive Farbenwelt', text)
        self.assertIsNone(probe.plain_json_probe(Http([]), '', 'model'))

    def test_realistic_response_validation_does_not_disclose_text(self):
        source = probe.synthetic_source(5000)
        items = [{'question': f'Frage {i}', 'options': ['eins', 'zwei', 'drei', 'vier'],
                  'correctIndex': 0, 'explanation': 'Erklärung', 'evidenceId': 1} for i in range(10)]
        payload = lambda x: json.dumps({'candidates': [{'content': {'parts': [{'text': json.dumps(x)}]}}]}).encode()
        http = Http([Response(200, payload(items)), Response(200, payload(items[:9]))])
        log = io.StringIO()
        with contextlib.redirect_stdout(log), contextlib.redirect_stderr(log):
            self.assertEqual(probe.request_status(http, 'private', 'model', {}, source), 'HTTP 200, zehn Quizfragen technisch gültig')
            self.assertEqual(probe.request_status(http, 'private', 'model', {}, source), 'HTTP 200, Quizantwort technisch ungültig')
        self.assertNotIn('Frage', log.getvalue())
        self.assertNotIn('private', log.getvalue())

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

    def test_model_override_is_allowlisted_and_local_to_probe(self):
        self.assertEqual(probe.selected_model('configured', 'configured-model'), 'configured-model')
        self.assertEqual(probe.selected_model('gemini-3.1-flash-lite', 'configured-model'), 'gemini-3.1-flash-lite')
        for invalid in ('', 'configured-model', 'https://attacker', '../foo'):
            self.assertIsNone(probe.selected_model(invalid, 'configured-model'))

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
