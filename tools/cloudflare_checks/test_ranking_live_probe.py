import contextlib
import io
import unittest
from unittest.mock import patch
from ranking_initialize import DB_ID
from ranking_provision import PreflightError
from ranking_live_probe import run

ENV = {'CLOUDFLARE_ACCOUNT_ID':'a'*32, 'CLOUDFLARE_API_TOKEN':'synthetic'}
BINDINGS = [
    {'name':'DB','type':'d1','id':DB_ID},
    {'name':'QUIZ_CATALOG_SERVICE','type':'service','service':'pflegeexamen-content'},
    *[{'name':n,'type':'ratelimit','namespace_id':i,'simple':{'limit':v,'period':60}} for n,i,v in
      [('REGISTRATION_RATE','74751001',5),('READ_RATE','74751002',120),('AUTH_RATE','74751003',60),('ACCOUNT_RATE','74751004',20)]]]

def fake_get(opener, path):
    if path == '/health': return 200, {'ok':True, 'service':'quiz-ranking-draft'}
    if path == '/leaderboard': return 200, {'generatedAtMillis':10,'nextUpdateAtMillis':20,'totalParticipants':0,'entries':[]}
    if path == '/quiz/today': return 404, {'error':'Heute ist noch kein Quiz veröffentlicht.'}
    raise AssertionError(path)

class LiveProbeTest(unittest.TestCase):
    def test_missing_daily_quiz_is_visible_and_not_claimed_tested(self):
        out = io.StringIO()
        with patch('ranking_live_probe.request', return_value={'result':{'bindings':BINDINGS}}), patch('ranking_live_probe.get', fake_get), contextlib.redirect_stdout(out):
            run(ENV, None)
        self.assertIn('Abgabe kann derzeit nicht getestet werden', out.getvalue())
        self.assertNotIn('synthetic', out.getvalue())
    def test_wrong_database_never_calls_public_service(self):
        with patch('ranking_live_probe.request', return_value={'result':{'bindings':BINDINGS[1:]}}), patch('ranking_live_probe.get') as get, self.assertRaises(PreflightError):
            run(ENV, None)
        get.assert_not_called()
    def test_bad_health_fails(self):
        with patch('ranking_live_probe.request', return_value={'result':{'bindings':BINDINGS}}), patch('ranking_live_probe.get', return_value=(200,{})), self.assertRaises(PreflightError):
            run(ENV, None)

if __name__ == '__main__': unittest.main()
