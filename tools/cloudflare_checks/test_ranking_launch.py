import contextlib
import io
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from ranking_initialize import DB_ID, OBJECTS
from ranking_provision import CONTENT_ID, NAME, PreflightError
from ranking_launch import run, verify_config

ENV = {'CLOUDFLARE_ACCOUNT_ID':'a'*32, 'CLOUDFLARE_API_TOKEN':'synthetic', 'RANKING_CONFIRM':'true'}

class LaunchTest(unittest.TestCase):
    def test_reviewed_config_has_expected_bindings(self): verify_config()
    def test_preflight_refuses_existing_worker_and_nonempty_database(self):
        def fake(opener, base, token, path, *args):
            if path.endswith('/pflegeexamen-content/settings'):
                return {'result':{'bindings':[{'name':'DB','type':'d1','id':CONTENT_ID}]}}
            if path.endswith('/d1/database/'+DB_ID): return {'result':{'uuid':DB_ID,'name':NAME}}
            if path.endswith('/pflege-examen-ranking/settings'): return {'result':{'bindings':[]}}
            if path.endswith('/workers/subdomain'): return {'result':{'subdomain':'badnudin'}}
            raise AssertionError(path)
        with patch('ranking_launch.request', fake), patch('ranking_launch.objects', return_value=OBJECTS), \
             patch('ranking_launch.query', return_value=[{'results':[{'participants':0,'attempts':0,'deleted':0,'closures':0}]}]):
            with self.assertRaises(PreflightError): run(ENV, None)
        with patch('ranking_launch.request', fake), patch('ranking_launch.objects', return_value=OBJECTS), \
             patch('ranking_launch.query', return_value=[{'results':[{'participants':1,'attempts':0,'deleted':0,'closures':0}]}]):
            with self.assertRaises(PreflightError): run(ENV, None)
    def test_success_only_for_absent_worker(self):
        def fake(opener, base, token, path, *args):
            if path.endswith('/pflegeexamen-content/settings'):
                return {'result':{'bindings':[{'name':'DB','type':'d1','id':CONTENT_ID}]}}
            if path.endswith('/d1/database/'+DB_ID): return {'result':{'uuid':DB_ID,'name':NAME}}
            if path.endswith('/pflege-examen-ranking/settings'): raise HTTPError('',404,'',{},None)
            if path.endswith('/workers/subdomain'): return {'result':{'subdomain':'badnudin'}}
            raise AssertionError(path)
        with patch('ranking_launch.request', fake), patch('ranking_launch.objects', return_value=OBJECTS), \
             patch('ranking_launch.query', return_value=[{'results':[{'participants':0,'attempts':0,'deleted':0,'closures':0}]}]), contextlib.redirect_stdout(io.StringIO()):
            run(ENV, None)
    def test_missing_confirmation_sends_nothing(self):
        with patch('ranking_launch.request') as request, self.assertRaises(PreflightError):
            run({**ENV,'RANKING_CONFIRM':'false'}, None)
        request.assert_not_called()

if __name__ == '__main__': unittest.main()
