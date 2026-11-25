import contextlib
import io
import unittest
from unittest.mock import patch

import ranking_live_snapshot_probe as probe
from ranking_live_account_probe import ProbeError


class SnapshotProbeTests(unittest.TestCase):
    def test_waits_until_next_hour_then_checks_exact_score(self):
        boards = [
            (200, {'generatedAtMillis': 3_599_000, 'entries': []}),
            (200, {'generatedAtMillis': 3_600_100, 'entries': [{'username': 'Probe-test', 'points': 13, 'quizzes': 1}]})]
        with patch.object(probe, 'get', side_effect=boards), patch.object(probe.time, 'monotonic', side_effect=[0, 1, 2]), contextlib.redirect_stdout(io.StringIO()):
            pauses = []
            probe.wait_for_snapshot(None, 'Probe-test', 13, now=lambda: 3599, pause=pauses.append)
        self.assertEqual(pauses, [30])

    def test_missing_wrong_and_inconclusive_entries_fail(self):
        for entries in ([], [{'username': 'Probe-test', 'points': 12, 'quizzes': 1}],
                        [{'username': f'other-{i}', 'points': 50, 'quizzes': 1} for i in range(100)]):
            with self.subTest(entries=len(entries)), patch.object(probe, 'get', return_value=(200, {'generatedAtMillis':3600000,'entries':entries})), contextlib.redirect_stdout(io.StringIO()), self.assertRaises(ProbeError):
                probe.wait_for_snapshot(None, 'Probe-test', 13, now=lambda: 3599, pause=lambda _: None)

    def test_callback_failure_still_uses_attempt_cleanup(self):
        # The shared attempt probe owns the try/finally; exercise the hook on
        # its real control flow with synthetic responses only.
        import datetime as dt
        import hashlib
        import json
        from zoneinfo import ZoneInfo
        import ranking_live_attempt_probe as attempt
        day = dt.datetime(2026, 11, 13, 12, tzinfo=ZoneInfo('Europe/Berlin'))
        quiz = {'date':day.date().isoformat(),'fingerprint':'b'*64,'questions':[
            {'id':str(i),'options':['a','b','c','d'],'correctIndex':0} for i in range(50)]}
        body, score = attempt.build_attempt(quiz, day)
        digest = hashlib.sha256(json.dumps(body, separators=(',', ':')).encode()).hexdigest()
        calls = []
        def call(_opener, method, path, _token, _body=None):
            calls.append((method, path))
            if path == '/participants': return 201, None, b'{"username":"Probe-test"}'
            if method == 'DELETE': return 204, 'v1', b''
            if path == '/quiz/attempt' and calls.count(('POST', path)) == 1:
                return 201, None, json.dumps({'date':body['date'],'correct':score,'total':50}).encode()
            if path == '/quiz/attempt': return 409, None, b'{"error":"Dieses Tagesquiz wurde bereits gewertet."}'
            return 200, None, json.dumps({'date':body['date'],'correct':score,'total':50,'payloadHash':digest}).encode()
        def fail(*_): raise ProbeError('snapshot failed')
        with patch.object(attempt, 'get', return_value=(200, quiz)), patch.object(attempt, 'call', side_effect=call), contextlib.redirect_stdout(io.StringIO()), self.assertRaises(ProbeError):
            attempt.run(None, 'a'*64, 'Probe-test', day, on_recorded=fail)
        self.assertEqual(calls[-1], ('DELETE', '/participants/me'))


if __name__ == '__main__': unittest.main()
