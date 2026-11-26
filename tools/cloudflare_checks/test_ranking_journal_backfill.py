import contextlib
import io
import sqlite3
import unittest
from unittest.mock import patch
import ranking_journal_backfill as b
from ranking_provision import PreflightError

ENV = {'CLOUDFLARE_ACCOUNT_ID':'a'*32, 'CLOUDFLARE_API_TOKEN':'x', 'GITHUB_REF':'refs/heads/main',
       'GITHUB_EVENT_NAME':'workflow_dispatch', 'CONFIRM_JOURNAL':'true'}

class Api:
    def __init__(self, guard=True):
        self.primary, self.journal = sqlite3.connect(':memory:'), sqlite3.connect(':memory:')
        self.primary.executescript(b.SCHEMA_PATH.read_text())
        if guard: self.primary.executescript(b.GUARD.read_text())
        self.journal.executescript(b.JOURNAL_SCHEMA.read_text())
        self.primary.executemany('INSERT INTO quiz_deleted_tokens VALUES (?,?)', [('a'*64,1),('b'*64,2)])
        self.primary.commit(); self.writes=0
    def __call__(self,opener,base,token,path,method='GET',data=None):
        for ident,name,db in [(b.DB_ID,b.NAME,self.primary),(b.JOURNAL_ID,b.JOURNAL_NAME,self.journal)]:
            if path == '/d1/database/' + ident: return {'result':{'uuid':ident,'name':name}}
            if path == '/d1/database/' + ident + '/query':
                cur=db.execute(data['sql'],data.get('params',[]))
                if cur.description:
                    out=[dict(zip([x[0] for x in cur.description],row)) for row in cur.fetchall()]
                else:
                    db.commit(); out=[]
                    if ident == b.JOURNAL_ID: self.writes+=1
                return {'result':[{'success':True,'results':out}]}
        raise AssertionError(path)

class BackfillTests(unittest.TestCase):
    def test_backfill_and_retry_are_idempotent_without_logs(self):
        api=Api(); output=io.StringIO()
        with patch.object(b,'request',side_effect=api),contextlib.redirect_stdout(output):
            b.run(ENV,None); b.run(ENV,None)
        self.assertEqual(api.journal.execute('SELECT token_hash,recorded_at FROM deletion_journal ORDER BY token_hash').fetchall(),[('a'*64,1),('b'*64,2)])
        self.assertNotIn('a'*64,output.getvalue()); self.assertNotIn('b'*64,output.getvalue())
        self.assertEqual(api.primary.execute('SELECT count(*) FROM quiz_deleted_tokens').fetchone()[0],2)
    def test_missing_guard_or_outstanding_permit_prevents_any_copy(self):
        for api in (Api(guard=False), Api()):
            if api.primary.execute("SELECT count(*) FROM sqlite_master WHERE name='quiz_journal_delete_permits'").fetchone()[0]:
                api.primary.execute('INSERT INTO quiz_journal_delete_permits VALUES (?)',('c'*64,)); api.primary.commit()
            with patch.object(b,'request',side_effect=api),self.assertRaises(PreflightError): b.run(ENV,None)
            self.assertEqual(api.writes,0)
    def test_invalid_barrier_and_wrong_ref_fail_closed(self):
        api=Api(); api.primary.execute('INSERT INTO quiz_deleted_tokens VALUES (?,?)',('invalid',3)); api.primary.commit()
        with patch.object(b,'request',side_effect=api),self.assertRaises(PreflightError): b.run(ENV,None)
        api=Api()
        with patch.object(b,'request',side_effect=api),self.assertRaises(PreflightError): b.run({**ENV,'GITHUB_REF':'refs/heads/no'},None)
        self.assertEqual(api.writes,0)

if __name__ == '__main__': unittest.main()
