import sqlite3
import unittest
from unittest.mock import patch

import ranking_admin_delete as admin
from ranking_provision import PreflightError
from ranking_initialize import SCHEMA_PATH

A = '11111111-1111-4111-8111-111111111111'
B = '22222222-2222-4222-8222-222222222222'
TA = 'a'*64
TB = 'b'*64
DB = '12345678-1234-1234-1234-123456789abc'

class FakeApi:
    def __init__(self):
        self.db = sqlite3.connect(':memory:')
        self.db.executescript(SCHEMA_PATH.read_text())
        self.db.executemany('INSERT INTO quiz_participants VALUES (?, ?, ?, ?)', [(A,TA,'A',1),(B,TB,'B',1)])
        self.db.executemany('INSERT INTO quiz_attempts VALUES (?, ?, ?, ?, ?, ?)', [(A,'2026-01-01',1,50,1,None),(B,'2026-01-01',2,50,1,None)])
        self.db.execute("INSERT INTO quiz_attempt_closures VALUES (?, '2026-01-02', 1)",(A,))
        self.db.execute("INSERT INTO quiz_hourly_snapshot VALUES (1, '{}', 1)")
        self.db.commit()
        self.batch_calls = 0
    def call(self, opener, base, token, path, method='GET', data=None):
        if path == '/d1/database/' + DB: return {'result':{'uuid':DB,'name':'fixture'}}
        assert path == '/d1/database/' + DB + '/query'
        if 'batch' in data:
            self.batch_calls += 1
            with self.db:
                for statement in data['batch']: self.db.execute(statement['sql'],statement['params'])
            return {'result':[{'success':True} for _ in data['batch']]}
        cursor = self.db.execute(data['sql'],data['params'])
        return {'result':[{'success':True, 'results':[dict(zip([d[0] for d in cursor.description], row)) for row in cursor.fetchall()]}]}

class AdminDeleteTests(unittest.TestCase):
    def execute(self, api, participant=A, token_hash=TA):
        with patch.object(admin, 'request', side_effect=api.call):
            admin.delete_verified(None, 'unused', 'secret', DB, 'fixture', participant, token_hash)
    def test_targeted_deletion_preserves_other_user_and_invalidates_snapshot(self):
        api=FakeApi(); self.execute(api)
        self.assertEqual(api.batch_calls,1)
        self.assertEqual(api.db.execute('SELECT id FROM quiz_participants').fetchall(), [(B,)])
        self.assertEqual(api.db.execute('SELECT participant_id FROM quiz_attempts').fetchall(), [(B,)])
        self.assertEqual(api.db.execute('SELECT token_hash FROM quiz_deleted_tokens').fetchall(), [(TA,)])
        self.assertEqual(api.db.execute('SELECT COUNT(*) FROM quiz_hourly_snapshot').fetchone()[0],0)
        self.assertEqual(api.db.execute('SELECT generation FROM quiz_snapshot_generation').fetchone()[0],1)
        with self.assertRaises(sqlite3.IntegrityError): api.db.execute('INSERT INTO quiz_participants VALUES (?, ?, ?, 1)',(A,TA,'A'))
    def test_mismatch_refuses_write(self):
        api=FakeApi()
        with self.assertRaises(PreflightError): self.execute(api, A, TB)
        self.assertEqual(api.batch_calls,0)
        self.assertEqual(api.db.execute('SELECT COUNT(*) FROM quiz_participants').fetchone()[0],2)
    def test_bad_identifiers_never_reach_api(self):
        with patch.object(admin,'request') as api, self.assertRaises(PreflightError):
            admin.delete_verified(None,'','',DB,'fixture','bad',TA)
        api.assert_not_called()
    def test_live_synthetic_test_requires_confirmation(self):
        import ranking_admin_delete_synthetic as synthetic
        with patch.object(synthetic, 'request') as request, self.assertRaises(PreflightError):
            synthetic.run({'CLOUDFLARE_ACCOUNT_ID':'a'*32, 'CLOUDFLARE_API_TOKEN':'secret', 'ADMIN_TEST_CONFIRM':'false'}, None)
        request.assert_not_called()
        self.assertNotIn(synthetic.TARGET, (synthetic.DB_ID, synthetic.CONTENT_ID))

    def test_wrong_db_name_refuses_write(self):
        api=FakeApi()
        with patch.object(admin,'request',side_effect=api.call), self.assertRaises(PreflightError):
            admin.delete_verified(None,'','',DB,'prod',A,TA)
        self.assertEqual(api.batch_calls,0)

if __name__ == '__main__': unittest.main()
