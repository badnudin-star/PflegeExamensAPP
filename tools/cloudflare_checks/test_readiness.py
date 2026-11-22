import io
import json
import unittest
from urllib.error import HTTPError

from readiness import MAX_RESPONSE, diagnose

ACCOUNT = 'a' * 32
TOKEN = 'synthetic-token-never-a-real-secret'
ENV = {'CLOUDFLARE_ACCOUNT_ID': ACCOUNT, 'CLOUDFLARE_API_TOKEN': TOKEN}


class Reply:
    status = 200
    def __init__(self, result): self.body = json.dumps({'success':True, 'result':result}).encode()
    def __enter__(self): return self
    def __exit__(self, *args): pass
    def read(self, size): return self.body[:size]


class Opener:
    def __init__(self): self.requests = []
    def open(self, request, timeout):
        self.requests.append(request)
        if request.full_url.endswith('/settings'):
            return Reply({'bindings':[{'name':'DB', 'type':'d1', 'database_id':'never-displayed'}]})
        return Reply([{'name':'private-database-name', 'uuid':'never-displayed'}])


class ReadinessTest(unittest.TestCase):
    def test_missing_or_malformed_configuration_never_sends_requests(self):
        for env in ({}, {'CLOUDFLARE_API_TOKEN':TOKEN}, {**ENV,'CLOUDFLARE_ACCOUNT_ID':'../../bad'}):
            opener = Opener()
            ok, messages = diagnose(env, opener)
            self.assertFalse(ok)
            self.assertEqual(opener.requests, [])
            self.assertNotIn(TOKEN, str(messages))

    def test_only_fixed_https_gets_no_response_details_or_credentials_in_output(self):
        opener = Opener()
        ok, messages = diagnose(ENV, opener)
        self.assertTrue(ok, messages)
        self.assertEqual(len(opener.requests), 2)
        for req in opener.requests:
            self.assertEqual(req.get_method(), 'GET')
            self.assertTrue(req.full_url.startswith('https://api.cloudflare.com/client/v4/accounts/'+ACCOUNT+'/'))
            self.assertEqual(req.get_header('Authorization'), 'Bearer '+TOKEN)
        for value in (TOKEN, ACCOUNT, 'never-displayed', 'private-database-name'):
            self.assertNotIn(value, str(messages))

    def test_missing_binding_http_errors_and_bad_response_fail_safely(self):
        class Missing(Opener):
            def open(self, request, timeout): return Reply({'bindings':[]} if request.full_url.endswith('/settings') else [])
        self.assertFalse(diagnose(ENV, Missing())[0])
        class Denied(Opener):
            def open(self, request, timeout): raise HTTPError(request.full_url, 403, TOKEN, {}, io.BytesIO(b'private'))
        ok, messages = diagnose(ENV, Denied())
        self.assertFalse(ok)
        self.assertIn('HTTP 403', str(messages))
        self.assertNotIn(TOKEN, str(messages))
        class Oversize(Opener):
            def open(self, request, timeout):
                result = Reply([]); result.body = b'X' * (MAX_RESPONSE + 1); return result
        self.assertFalse(diagnose(ENV, Oversize())[0])


if __name__ == '__main__': unittest.main()
