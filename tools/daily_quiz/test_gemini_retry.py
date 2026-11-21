import io
import json
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch

import quiz_pipeline as qp


class Response:
    def __init__(self, status):
        self.status_code = status
    def json(self):
        return {'candidates': [{'content': {'parts': [{'text': json.dumps([{'question': 'test'}])}]}}]}


class Http:
    def __init__(self, statuses):
        self.statuses = iter(statuses)
        self.requests = []
    def post(self, url, **kwargs):
        self.requests.append((url, kwargs))
        status = next(self.statuses)
        if isinstance(status, Exception):
            raise status
        return Response(status)


class GeminiRetryTests(unittest.TestCase):
    def call(self, http):
        return qp.gemini_request(http, 'private-test-key', 'test-model', 'Thema', 'private source text ' * 10)

    @patch('quiz_pipeline.random.uniform', return_value=0.5)
    @patch('quiz_pipeline.time.sleep')
    def test_success_after_two_503s(self, sleep, jitter):
        http = Http([503, 503, 200])
        log = io.StringIO()
        with redirect_stdout(log):
            self.assertEqual(self.call(http), [{'question': 'test'}])
        self.assertEqual(len(http.requests), 3)
        self.assertEqual(http.requests[0], http.requests[1])
        self.assertEqual(http.requests[1], http.requests[2])
        self.assertEqual([c.args[0] for c in sleep.call_args_list], [5.5, 10.5])
        self.assertEqual(jitter.call_count, 2)
        self.assertNotIn('private', log.getvalue())
        self.assertIn('3 von 3', log.getvalue())

    @patch('quiz_pipeline.time.sleep')
    def test_503_exhausts_three_attempts(self, sleep):
        http = Http([503, 503, 503, 200])
        with self.assertRaisesRegex(ValueError, 'HTTP 503'):
            self.call(http)
        self.assertEqual(len(http.requests), 3)
        self.assertEqual(sleep.call_count, 2)

    @patch('quiz_pipeline.time.sleep')
    def test_other_http_errors_and_network_exceptions_do_not_retry(self, sleep):
        for status in (400, 403, 429, 500, RuntimeError('network')):
            http = Http([status, 200])
            with self.subTest(status=status), self.assertRaises((ValueError, RuntimeError)):
                self.call(http)
            self.assertEqual(len(http.requests), 1)
        sleep.assert_not_called()


if __name__ == '__main__':
    unittest.main()
