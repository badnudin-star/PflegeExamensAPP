import sqlite3
import unittest
from unittest.mock import patch
import ranking_journal_initialize as init
from ranking_provision import PreflightError

ENV = {'CLOUDFLARE_ACCOUNT_ID':'a'*32, 'CLOUDFLARE_API_TOKEN':'x', 'GITHUB_REF':'refs/heads/main',
       'GITHUB_EVENT_NAME':'workflow_dispatch', 'CONFIRM_JOURNAL':'true'}

class Api:
    def __init__(self, wrong=False):
        self.db = sqlite3.connect(':memory:'); self.wrong = wrong; self.writes = 0
    def __call__(self, opener, base, token, path, method='GET', data=None):
        if path == '/d1/database/' + init.JOURNAL_ID:
            return {'result':{'uuid':init.JOURNAL_ID,'name':'wrong' if self.wrong else init.JOURNAL_NAME}}
        assert path == '/d1/database/' + init.JOURNAL_ID + '/query'
        sql = data['sql']
        if sql == init.SCHEMA.read_text():
            self.writes += 1; self.db.executescript(sql)
            return {'result':[{'success':True,'results':[]}]}
        cur = self.db.execute(sql)
        return {'result':[{'success':True, 'results':[dict(zip([d[0] for d in cur.description], row)) for row in cur.fetchall()]}]}

class InitializeJournalTests(unittest.TestCase):
    def test_only_empty_separate_db_initialized(self):
        api=Api()
        with patch.object(init, 'request', side_effect=api): init.run(ENV, None)
        self.assertEqual(api.writes,1)
        with patch.object(init, 'request', side_effect=api), self.assertRaises(PreflightError): init.run(ENV,None)
        self.assertEqual(api.writes,1)
    def test_wrong_identity_or_ref_does_not_write(self):
        for api, env in [(Api(wrong=True),ENV),(Api(),{**ENV,'GITHUB_REF':'refs/heads/other'})]:
            with patch.object(init,'request',side_effect=api), self.assertRaises(PreflightError): init.run(env,None)
            self.assertEqual(api.writes,0)
    def test_production_database_never_used_as_journal(self):
        api=Api()
        with patch.object(init,'JOURNAL_ID',init.DB_ID), patch.object(init,'request',side_effect=api), self.assertRaises(PreflightError): init.run(ENV,None)
        self.assertEqual(api.writes,0)

if __name__ == '__main__': unittest.main()
