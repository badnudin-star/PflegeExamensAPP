import copy
import datetime as dt
import sys
import unittest
from unittest.mock import patch
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
import quiz_pipeline as qp

SOURCE='Dies ist ein ausreichend langer wörtlicher Beleg aus dem eigenen Lernzettel.'
DATE=dt.date(2026,11,18)
def gen(topic,source):
    return [{'question':f'{topic} Frage {n}', 'options':['A','B','C','D'], 'correctIndex':1, 'explanation':'Erklärung', 'evidenceId':1} for n in range(10)]
class QuizTests(unittest.TestCase):
    def setUp(self):
        # Synthetic catalog sizes must never be written to a real Actions summary.
        env = patch.dict('os.environ', {'GITHUB_STEP_SUMMARY': ''})
        env.start()
        self.addCleanup(env.stop)

    def draft(self):
        from import_notes import topic_candidates
        index={name:{'name':name} for t in qp.select_topics(DATE) for name in topic_candidates(qp.TOPICS.index(t))[0]}
        return qp.create_draft(index,lambda f:SOURCE+' weiterer Text'*10,gen,DATE)
    def test_draft_exactly_50_and_topics_rotate(self):
        d=self.draft();self.assertEqual(len(d['quizzes']),50)
        self.assertEqual(len({q['topic'] for q in d['quizzes']}),5)
        self.assertNotEqual(qp.select_topics(DATE),qp.select_topics(DATE+dt.timedelta(days=1)))
    def test_every_rotation_uses_a_known_current_source(self):
        from import_notes import expected_names, topic_candidates
        available = expected_names()
        for day in range(42):
            for topic in qp.select_topics(DATE + dt.timedelta(days=day)):
                filename = topic_candidates(qp.TOPICS.index(topic))[0][0]
                self.assertIn(filename, available)
                self.assertEqual(available[filename][1], 'detailed')

    def test_draft_rejects_missing_new_source(self):
        with self.assertRaisesRegex(ValueError, 'Fehlende Quelle'):
            qp.create_draft({}, lambda _: SOURCE, gen, DATE)

    def test_bad_sources_duplicates_and_options_rejected(self):
        items=gen('Thema',SOURCE);items[0]['evidenceId']=999
        with self.assertRaises(ValueError): qp.validate_generated(items,'Thema','x.pdf',SOURCE + ' Weitere Informationen.',DATE.isoformat(),0)
        d=self.draft();d['quizzes'][1]['question']=d['quizzes'][0]['question']
        with self.assertRaises(ValueError): qp.validate_draft(d)
        d=self.draft();d['quizzes'][0]['correctIndex']=4
        with self.assertRaises(ValueError): qp.validate_draft(d)
    def test_annotation_exposes_only_safe_diagnostics(self):
        self.assertEqual(qp.diagnostic(ValueError('Gemini-Anfrage fehlgeschlagen (HTTP 429); kein Quiz veröffentlicht')),
                         'Gemini-Anfrage fehlgeschlagen (HTTP 429).')
        self.assertEqual(qp.diagnostic(ValueError('Quellenzitat nicht im Lernzettel gefunden')),
                         'Ein erzeugter Quellenbeleg steht nicht im Lernzettel.')
        for error in (ValueError('secret or prompt'), RuntimeError('secret or prompt'),
                      ValueError('Gemini-Anfrage fehlgeschlagen (HTTP 429); secret')):
            self.assertNotIn('secret', qp.diagnostic(error))

    def test_quiz_candidate_size_is_exact_and_oversize_still_prevents_network_write(self):
        base={'schemaVersion':1,'revision':3,'notes':[],'cards':[],'cases':[],'quizzes':[]}
        draft=self.draft()
        class Response: status_code=200
        class Http:
            def __init__(self): self.requests=[]
            def put(self,url,**kwargs): self.requests.append((url,kwargs)); return Response()
        http=Http()
        with patch.object(qp, 'read_remote', return_value=copy.deepcopy(base)), patch.object(qp, 'report_capacity') as report:
            qp.publish(http,'https://example.org','x'*32,draft,base)
            report.assert_called_once_with(len(http.requests[0][1]['data']), 'quiz_candidate')
        blocked=Http()
        with patch.object(qp, 'read_remote', return_value=copy.deepcopy(base)), patch.object(qp, 'MAX_CATALOG', 100), patch.object(qp, 'report_capacity') as report:
            with self.assertRaisesRegex(ValueError, 'Größenlimit'):
                qp.publish(blocked,'https://example.org','x'*32,draft,base)
            self.assertEqual(len(blocked.requests),0)
            self.assertGreater(report.call_args.args[0],100)

    def test_publication_preserves_catalog_and_rejects_conflicts(self):
        base={'schemaVersion':1,'revision':3,'notes':[],'cards':[],'cases':[],'quizzes':[]}; draft=self.draft()
        class Response:
            status_code=200
        class Http:
            def put(self,url,**kwargs): self.kwargs=kwargs;return Response()
        http=Http();old=qp.read_remote
        try:
            qp.read_remote=lambda h,u:copy.deepcopy(base)
            qp.publish(http,'https://example.org','x'*32,draft,base)
            import json
            out=json.loads(http.kwargs['data']);self.assertEqual(out['revision'],4);self.assertEqual(out['notes'],base['notes']);self.assertEqual(len(out['quizzes']),50);self.assertEqual(http.kwargs['headers']['If-Match'],'3')
            existing=copy.deepcopy(base);existing['quizzes']=draft['quizzes']
            qp.read_remote=lambda h,u:existing
            with self.assertRaises(ValueError): qp.publish(http,'https://example.org','x'*32,draft,base)
            qp.read_remote=lambda h,u:base
            class Conflict:
                def put(self,*a,**kw):
                    result=Response();result.status_code=409;return result
            with self.assertRaises(ValueError): qp.publish(Conflict(),'https://example.org','x'*32,draft,base)
        finally: qp.read_remote=old
if __name__=='__main__': unittest.main()
