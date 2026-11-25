import contextlib
import io
import sqlite3
import unittest
from unittest.mock import patch

import ranking_restore_synthetic as probe
from ranking_provision import PreflightError

ENV = {'CLOUDFLARE_API_TOKEN':'test-secret','CLOUDFLARE_ACCOUNT_ID':'a'*32,'RESTORE_CONFIRM':'true'}
TARGET = '12345678-1234-1234-1234-123456789abc'

class FakeApi:
    def __init__(self): self.db = sqlite3.connect(':memory:'); self.name = None; self.calls = []
    def call(self, opener, base, token, path, method='GET', data=None):
        self.calls.append((path,method))
        if path == '/d1/database':
            self.name = data['name']
            return {'result':{'name':self.name,'uuid':TARGET}}
        if path == '/d1/database/' + TARGET: return {'result':{'name':self.name,'uuid':TARGET}}
        if path == '/d1/database/' + TARGET + '/query':
            sql = data['sql']; params = data['params']
            if 'CREATE TABLE IF NOT EXISTS quiz_participants' in sql:
                self.db.executescript(sql); results = []
            else:
                c = self.db.execute(sql, params)
                results = [dict(zip([d[0] for d in c.description], row)) for row in c.fetchall()] if c.description else []
            return {'result':[{'success':True,'results':results}]}
        raise AssertionError('Unexpected target')

class RestoreTests(unittest.TestCase):
    def test_confirmation_required_before_any_request(self):
        with patch.object(probe, 'request') as request, self.assertRaises(PreflightError):
            probe.run({**ENV, 'RESTORE_CONFIRM':'false'}, None)
        request.assert_not_called()

    def test_old_backup_excludes_later_deleted_account(self):
        api = FakeApi(); output = io.StringIO()
        with patch.object(probe, 'request', side_effect=api.call), contextlib.redirect_stdout(output):
            probe.run(ENV, None)
        self.assertEqual(api.db.execute('SELECT id FROM quiz_participants').fetchall(), [('fixture-alive',)])
        self.assertEqual(api.db.execute('SELECT token_hash FROM quiz_deleted_tokens').fetchall(), [('b'*64,)])
        self.assertNotIn('test-secret', output.getvalue())
        self.assertTrue(all(probe.DB_ID not in path and probe.CONTENT_ID not in path for path, _ in api.calls))

    def test_refuses_production_uuid_before_query(self):
        def fake(_opener, _base, _token, path, method='GET', data=None):
            return {'result':{'name':data['name'],'uuid':probe.DB_ID}}
        with patch.object(probe, 'request', side_effect=fake) as request, contextlib.redirect_stdout(io.StringIO()), self.assertRaises(PreflightError):
            probe.run(ENV, None)
        self.assertEqual(request.call_count, 1)

    def test_rejects_nonempty_target(self):
        api = FakeApi()
        api.db.execute('CREATE TABLE existing (id INTEGER)')
        with patch.object(probe, 'request', side_effect=api.call), contextlib.redirect_stdout(io.StringIO()), self.assertRaises(PreflightError):
            probe.run(ENV, None)
        self.assertEqual(api.db.execute("SELECT COUNT(*) FROM sqlite_master WHERE name='existing'").fetchone()[0], 1)

if __name__ == '__main__': unittest.main()
