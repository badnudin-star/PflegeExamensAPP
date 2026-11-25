import contextlib
import io
import json
import sqlite3
import unittest
from unittest.mock import patch
from ranking_initialize import DB_ID, NAME, OBJECTS, PreflightError, run

ENV = {'CLOUDFLARE_ACCOUNT_ID':'a'*32, 'CLOUDFLARE_API_TOKEN':'synthetic', 'RANKING_CONFIRM':'true'}

class Reply:
    def __init__(self, result): self.body = json.dumps({'success':True, 'result':result}).encode()
    def __enter__(self): return self
    def __exit__(self, *args): pass
    def read(self, limit): return self.body[:limit]

class Opener:
    def __init__(self, name=NAME):
        self.db = sqlite3.connect(':memory:')
        self.db.row_factory = sqlite3.Row
        self.name = name
        self.requests = []
    def open(self, req, timeout):
        self.requests.append(req)
        if req.get_method() == 'GET': return Reply({'uuid':DB_ID, 'name':self.name})
        sql = json.loads(req.data)['sql']
        if sql.startswith('SELECT'):
            rows = [dict(r) for r in self.db.execute(sql)]
        else:
            self.db.executescript(sql); rows = []
        return Reply([{'success':True, 'results':rows}])

class InitializeTest(unittest.TestCase):
    def test_empty_dedicated_database_is_initialized_and_verified(self):
        o = Opener()
        with contextlib.redirect_stdout(io.StringIO()): run(ENV, o)
        got = {(r['type'], r['name']) for r in o.db.execute("SELECT type,name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'")}
        self.assertEqual(got, OBJECTS)
        self.assertEqual(o.db.execute('SELECT generation FROM quiz_snapshot_generation').fetchone()[0], 0)
    def test_existing_objects_never_modify_database(self):
        o = Opener(); o.db.execute('CREATE TABLE existing (a INT)')
        with self.assertRaises(PreflightError): run(ENV, o)
        self.assertEqual(len(o.requests), 2)
        self.assertEqual(o.db.execute("SELECT count(*) FROM sqlite_master WHERE name='existing'").fetchone()[0], 1)
    def test_confirmation_identity_and_schema_hash_fail_closed(self):
        for env, name in [({**ENV,'RANKING_CONFIRM':'false'}, NAME), (ENV, 'not-ranking')]:
            o = Opener(name)
            with self.assertRaises(PreflightError): run(env, o)
            self.assertFalse(any(r.get_method() == 'POST' for r in o.requests))
        o = Opener()
        with patch('ranking_initialize.SCHEMA_HASH', 'wrong'), self.assertRaises(PreflightError): run(ENV, o)
        self.assertEqual(o.requests, [])

if __name__ == '__main__': unittest.main()
