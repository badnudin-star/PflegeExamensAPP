import contextlib
import datetime as dt
import hashlib
import io
import json
import unittest
from unittest.mock import patch
from zoneinfo import ZoneInfo

import ranking_live_attempt_probe as probe

TOKEN = 'a' * 64
NAME = 'Probe-0123456789ab'
NOW = dt.datetime(2026, 11, 13, 12, tzinfo=ZoneInfo('Europe/Berlin'))
QUIZ = {'date': NOW.date().isoformat(), 'fingerprint': 'b' * 64, 'questions': [
    {'id': f'frage-{i}', 'options': ['a', 'b', 'c', 'd'], 'correctIndex': i % 4}
    for i in range(50)]}


class AttemptProbeTests(unittest.TestCase):
    def test_build_rejects_missing_or_old_and_duplicate_quizzes(self):
        for bad in ({**QUIZ, 'date': '2020-01-01'}, {**QUIZ, 'questions': QUIZ['questions'][:49]},
                    {**QUIZ, 'questions': [QUIZ['questions'][0]] * 50}, {**QUIZ, 'fingerprint': 'x' * 64}):
            with self.assertRaises(probe.ProbeError): probe.build_attempt(bad, NOW)
        with self.assertRaises(probe.ProbeError): probe.build_attempt(QUIZ, NOW.replace(hour=23, minute=55))

    def test_no_quiz_means_no_test_account(self):
        with patch.object(probe, 'get', return_value=(404, {'error':'Heute ist noch kein Quiz veröffentlicht.'})), patch.object(probe, 'call') as call:
            with self.assertRaises(probe.ProbeError): probe.run(None, TOKEN, NAME, NOW, lambda _: None)
            call.assert_not_called()

    def test_complete_flow_checks_actual_request_hash_and_cleanup(self):
        calls = []
        attempt, expected = probe.build_attempt(QUIZ, NOW)
        digest = hashlib.sha256(json.dumps(attempt, separators=(',', ':')).encode()).hexdigest()
        def fake(opener, method, path, token, body=None):
            calls.append((method, path, body))
            if (method, path) == ('POST', '/participants'):
                return 201, None, json.dumps({'username': NAME}).encode()
            if path == '/quiz/attempt' and len([c for c in calls if c[1] == path]) == 1:
                self.assertEqual(body, attempt)
                return 201, None, json.dumps({'date': attempt['date'], 'correct': expected, 'total': 50}).encode()
            if path == '/quiz/attempt': return 409, None, json.dumps({'error':'Dieses Tagesquiz wurde bereits gewertet.'}).encode()
            if path.startswith('/quiz/attempt/result?'):
                return 200, None, json.dumps({'date':attempt['date'],'correct':expected,'total':50,'payloadHash':digest}).encode()
            if method == 'DELETE': return 204, 'v1', b''
            return 404, None, b'{}'
        output = io.StringIO()
        with patch.object(probe, 'get', return_value=(200, QUIZ)), patch.object(probe, 'call', side_effect=fake), contextlib.redirect_stdout(output):
            probe.run(None, TOKEN, NAME, NOW, lambda _: None)
        self.assertEqual([x[0] for x in calls], ['POST', 'POST', 'POST', 'GET', 'DELETE', 'GET'])
        self.assertNotIn(TOKEN, output.getvalue())
        self.assertNotIn('frage-', output.getvalue())

    def test_unrelated_conflict_is_not_treated_as_duplicate(self):
        calls = []
        attempt, expected = probe.build_attempt(QUIZ, NOW)
        def fake(opener, method, path, token, body=None):
            calls.append((method, path))
            if path == '/participants': return 201, None, json.dumps({'username':NAME}).encode()
            if method == 'DELETE': return 204, 'v1', b''
            if path == '/quiz/attempt' and len([c for c in calls if c[1] == path]) == 1:
                return 201, None, json.dumps({'date':attempt['date'],'correct':expected,'total':50}).encode()
            return 409, None, json.dumps({'error':'Quiz wurde geändert. Bitte neu laden.'}).encode()
        with patch.object(probe, 'get', return_value=(200, QUIZ)), patch.object(probe, 'call', side_effect=fake), contextlib.redirect_stdout(io.StringIO()), self.assertRaises(probe.ProbeError):
            probe.run(None, TOKEN, NAME, NOW, lambda _: None)
        self.assertEqual(calls[-1], ('DELETE', '/participants/me'))

    def test_failed_submission_still_deletes(self):
        calls = []
        def fake(opener, method, path, token, body=None):
            calls.append(method)
            if path == '/participants': return 201, None, json.dumps({'username':NAME}).encode()
            if method == 'DELETE': return 204, 'v1', b''
            raise OSError('synthetic failure')
        with patch.object(probe, 'get', return_value=(200, QUIZ)), patch.object(probe, 'call', side_effect=fake), contextlib.redirect_stdout(io.StringIO()), self.assertRaises(OSError):
            probe.run(None, TOKEN, NAME, NOW, lambda _: None)
        self.assertEqual(calls, ['POST', 'POST', 'DELETE'])

    def test_cleanup_without_receipt_fails_closed_and_does_not_reveal_token(self):
        def fake(opener, method, path, token, body=None):
            if method == 'POST' and path == '/participants': return 201, None, json.dumps({'username':NAME}).encode()
            return 401, None, b'{}'
        output = io.StringIO()
        with patch.object(probe, 'get', return_value=(200, QUIZ)), patch.object(probe, 'call', side_effect=fake), contextlib.redirect_stdout(output), self.assertRaises(probe.ProbeError):
            probe.run(None, TOKEN, NAME, NOW, lambda _: None)
        self.assertIn('Testkontolöschung nicht bestätigt', output.getvalue())
        self.assertNotIn(TOKEN, output.getvalue())


if __name__ == '__main__': unittest.main()
