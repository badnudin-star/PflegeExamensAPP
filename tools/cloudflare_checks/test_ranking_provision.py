import contextlib
import io
import json
import unittest
from urllib.error import HTTPError
from ranking_provision import CONTENT_ID, NAME, run

ENV = {'CLOUDFLARE_API_TOKEN': 'synthetic-token', 'CLOUDFLARE_ACCOUNT_ID': 'a' * 32,
       'RANKING_PROVISION_MODE': 'inspect', 'RANKING_CONFIRM': 'false'}

class Reply:
    def __init__(self, result, info=None):
        self.body = json.dumps({'success': True, 'result': result, 'result_info': info}).encode()
    def __enter__(self): return self
    def __exit__(self, *args): pass
    def read(self, limit): return self.body[:limit]

class Opener:
    def __init__(self, dbs=None, worker_exists=False, binding=CONTENT_ID):
        self.requests = []
        self.dbs = dbs or []
        self.worker_exists = worker_exists
        self.binding = binding
    def open(self, req, timeout):
        self.requests.append(req)
        path = req.full_url.split('/accounts/' + 'a'*32)[1]
        if path == '/workers/scripts/pflegeexamen-content/settings':
            return Reply({'bindings':[{'name':'DB', 'type':'d1', 'id':self.binding}]})
        if path.startswith('/d1/database?'):
            return Reply(self.dbs, {'count':len(self.dbs), 'page':1, 'per_page':100, 'total_count':len(self.dbs)})
        if path == '/workers/scripts/pflege-examen-ranking/settings':
            if self.worker_exists: return Reply({'bindings':[]})
            raise HTTPError(req.full_url, 404, 'not found', {}, io.BytesIO())
        if path == '/workers/subdomain': return Reply({'subdomain':'badnudin'})
        if path == '/d1/database' and req.get_method() == 'POST':
            return Reply({'name':NAME, 'uuid':'12345678-1234-1234-1234-123456789abc'})
        raise AssertionError(path)

class ProvisionTests(unittest.TestCase):
    def test_inspection_only_gets(self):
        opener = Opener(); run(ENV, opener)
        self.assertTrue(all(r.get_method() == 'GET' for r in opener.requests))
    def test_creation_is_confirmed_and_creates_only_exact_separate_name(self):
        opener = Opener(); output = io.StringIO()
        with contextlib.redirect_stdout(output):
            run({**ENV, 'RANKING_PROVISION_MODE':'create', 'RANKING_CONFIRM':'true'}, opener)
        self.assertIn('::notice title=Neue separate Ranking-D1::12345678-1234-1234-1234-123456789abc', output.getvalue())
        post = [r for r in opener.requests if r.get_method() == 'POST']
        self.assertEqual(len(post), 1)
        self.assertEqual(json.loads(post[0].data), {'name': NAME})
        self.assertTrue(post[0].full_url.endswith('/d1/database'))
    def test_create_http_error_does_not_expose_secret_or_retry(self):
        class Denied(Opener):
            def open(self, req, timeout):
                if req.get_method() == 'POST':
                    self.requests.append(req)
                    raise HTTPError(req.full_url, 401, 'private error', {}, io.BytesIO(b'secret'))
                return super().open(req, timeout)
        opener = Denied()
        output = io.StringIO()
        with contextlib.redirect_stdout(output), self.assertRaises(HTTPError):
            run({**ENV, 'RANKING_PROVISION_MODE':'create', 'RANKING_CONFIRM':'true'}, opener)
        self.assertNotIn('synthetic-token', output.getvalue())
        self.assertNotIn('secret', output.getvalue())
        self.assertIn('HTTP 401', output.getvalue())
        self.assertEqual(sum(r.get_method() == 'POST' for r in opener.requests), 1)
    def test_never_create_on_conflict_wrong_binding_or_missing_confirmation(self):
        for opener, env in [(Opener([{'name':NAME}]), ENV), (Opener(worker_exists=True), ENV),
                            (Opener(binding='wrong'), ENV), (Opener(), {**ENV, 'RANKING_CONFIRM':'false'})]:
            with self.assertRaises(ValueError): run({**env, 'RANKING_PROVISION_MODE':'create'}, opener)
            self.assertFalse(any(r.get_method() == 'POST' for r in opener.requests))

if __name__ == '__main__': unittest.main()
