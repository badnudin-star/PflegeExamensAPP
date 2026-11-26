import unittest
from unittest.mock import patch
import ranking_journal_provision as provision
from ranking_provision import PreflightError

NEW = 'e2b3d4f5-2345-4567-8901-abcdefabcdef'
ENV = {'CLOUDFLARE_ACCOUNT_ID':'a'*32, 'CLOUDFLARE_API_TOKEN':'x', 'GITHUB_REF':'refs/heads/main',
       'GITHUB_EVENT_NAME':'workflow_dispatch', 'CONFIRM_JOURNAL':'true'}

class Api:
    def __init__(self, existing=False, bad_ranking=False):
        self.existing, self.bad_ranking, self.posts = existing, bad_ranking, 0
    def __call__(self, opener, base, token, path, method='GET', data=None):
        if path == '/d1/database/' + provision.DB_ID:
            return {'result':{'uuid':provision.DB_ID, 'name':'wrong' if self.bad_ranking else provision.RANKING_NAME}}
        if path.startswith('/d1/database?'):
            items = [{'name':provision.JOURNAL_NAME}] if self.existing else []
            return {'result':items, 'result_info':{'count':len(items),'page':1,'per_page':100,'total_count':len(items)}}
        if path == '/d1/database' and method == 'POST':
            self.posts += 1
            assert data == {'name':provision.JOURNAL_NAME}
            return {'result':{'uuid':NEW, 'name':provision.JOURNAL_NAME}}
        raise AssertionError(path)

class JournalProvisionTests(unittest.TestCase):
    def test_creates_only_new_database(self):
        api = Api()
        with patch.object(provision, 'request', side_effect=api): provision.run(ENV, None)
        self.assertEqual(api.posts, 1)
    def test_refuses_existing_or_wrong_production_database(self):
        for api in (Api(existing=True), Api(bad_ranking=True)):
            with patch.object(provision, 'request', side_effect=api), self.assertRaises(PreflightError): provision.run(ENV,None)
            self.assertEqual(api.posts, 0)
    def test_requires_confirmation_and_main(self):
        for key, value in [('CONFIRM_JOURNAL','false'), ('GITHUB_REF','refs/heads/else'), ('GITHUB_EVENT_NAME','push')]:
            api = Api()
            with patch.object(provision, 'request', side_effect=api), self.assertRaises(PreflightError): provision.run({**ENV,key:value},None)
            self.assertEqual(api.posts, 0)
    def test_refuses_production_id_as_created(self):
        api = Api()
        def response(*args, **kwargs):
            result = api(*args, **kwargs)
            if args[3] == '/d1/database' and kwargs.get('method', args[4] if len(args)>4 else '') == 'POST':
                result['result']['uuid'] = provision.DB_ID
            return result
        with patch.object(provision, 'request', side_effect=response), self.assertRaises(PreflightError): provision.run(ENV,None)
        self.assertEqual(api.posts, 1)

if __name__ == '__main__': unittest.main()
