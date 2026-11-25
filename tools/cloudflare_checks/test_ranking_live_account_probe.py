import contextlib
import io
import json
import unittest
from unittest.mock import patch
import ranking_live_account_probe as probe

TOKEN = 'a' * 64
NAME = 'Probe-0123456789ab'


class AccountProbeTests(unittest.TestCase):
    def test_success_and_no_secret_output(self):
        calls = []
        def fake(opener, method, path, token, body=None):
            calls.append((method, path))
            if calls == [('POST', '/participants')]:
                return 201, None, json.dumps({'username': NAME}).encode()
            if method == 'GET' and len(calls) == 2:
                return 200, None, json.dumps({'username': NAME}).encode()
            if method == 'POST':
                return 200, None, json.dumps({'username': NAME}).encode()
            if method == 'DELETE':
                return 204, 'v1', b''
            return 404, None, b'{}'
        out = io.StringIO()
        with patch.object(probe, 'call', fake), contextlib.redirect_stdout(out):
            probe.run(None, TOKEN, NAME, lambda _: None)
        self.assertEqual([c[0] for c in calls], ['POST', 'GET', 'POST', 'DELETE', 'GET', 'DELETE'])
        self.assertNotIn(TOKEN, out.getvalue())

    def test_timed_out_registration_still_attempts_cleanup(self):
        calls = []
        def fake(opener, method, path, token, body=None):
            calls.append(method)
            if method == 'POST': raise OSError('synthetic timeout')
            return 204, 'v1', b''
        with patch.object(probe, 'call', fake), self.assertRaises(OSError):
            probe.run(None, TOKEN, NAME, lambda _: None)
        self.assertEqual(calls, ['POST', 'DELETE'])

    def test_missing_receipt_fails_closed_and_gives_nonsecret_identifier(self):
        out = io.StringIO()
        with patch.object(probe, 'call', return_value=(401, None, b'{}')), contextlib.redirect_stdout(out), self.assertRaises(probe.ProbeError):
            probe.run(None, TOKEN, NAME, lambda _: None)
        self.assertIn('Testkontolöschung nicht bestätigt', out.getvalue())
        self.assertIn(NAME, out.getvalue())
        self.assertIn('Token-SHA256=', out.getvalue())
        self.assertNotIn(TOKEN, out.getvalue())


if __name__ == '__main__': unittest.main()
