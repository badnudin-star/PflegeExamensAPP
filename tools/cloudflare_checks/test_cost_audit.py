import io
import json
import unittest
from urllib.error import HTTPError
from unittest.mock import patch

import cost_audit

class Response:
    def __init__(self, value): self.value = value
    def __enter__(self): return self
    def __exit__(self, *args): pass
    def read(self, _limit): return json.dumps(self.value).encode()

class AuditTests(unittest.TestCase):
    def test_missing_config_fails_without_network(self):
        with self.assertRaises(ValueError): cost_audit.audit({}, None)

    def test_denials_are_unknown_not_zero_and_no_secret(self):
        class Opener:
            def open(self, req, timeout):
                raise HTTPError(req.full_url, 403, 'denied', {}, io.BytesIO())
        with patch.object(cost_audit, 'read_json', side_effect=HTTPError('https://example', 403, 'denied', {}, io.BytesIO())):
            messages = cost_audit.audit({'CLOUDFLARE_API_TOKEN':'private-secret', 'CLOUDFLARE_ACCOUNT_ID':'a'*32}, Opener())
        self.assertEqual(len(messages), 3)
        self.assertTrue(all('unbekannt' in message for message in messages))
        self.assertNotIn('private-secret', str(messages))

    def test_aggregates_without_emitting_raw_data(self):
        class Opener:
            def open(self, req, timeout):
                self.assert_request(req)
                return Response({'data':{'viewer':{'accounts':[{'d1AnalyticsAdaptiveGroups':[
                    {'sum':{'rowsRead':7,'rowsWritten':3},'dimensions':{'date':'2026-11-13'}}]}]}}})
            def assert_request(self, req):
                assert req.full_url == 'https://api.cloudflare.com/client/v4/graphql'
                assert req.method == 'POST'
        values = [[{'rate_plan':{'id':'free'}}], [
            {'alert_type':'billing_budget_alert','enabled':True,'name':'private name'},
            {'alert_type':'billing_usage_alert','enabled':False}]]
        with patch.object(cost_audit, 'read_json', side_effect=values):
            messages = cost_audit.audit({'CLOUDFLARE_API_TOKEN':'secret', 'CLOUDFLARE_ACCOUNT_ID':'a'*32}, Opener())
        self.assertIn('1 aktive', messages[1])
        self.assertIn('7 gelesene und 3 geschriebene', messages[2])
        self.assertNotIn('private name', str(messages))
        self.assertNotIn('secret', str(messages))

if __name__ == '__main__': unittest.main()
