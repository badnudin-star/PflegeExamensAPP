import copy
import datetime as dt
import sys
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
import quiz_pipeline as qp

SOURCE='Dies ist ein ausreichend langer wörtlicher Beleg aus dem eigenen Lernzettel.'
DATE=dt.date(2026,11,18)
def gen(topic,source):
    return [{'question':f'{topic} Frage {n}', 'options':['A','B','C','D'], 'correctIndex':1, 'explanation':'Erklärung', 'evidence':SOURCE} for n in range(10)]
class QuizTests(unittest.TestCase):
    def draft(self):
        index={f'{qp.TOPICS.index(t)+1:02d}_{t[0]}_Ausfuehrlich.pdf':{'name':t[0]} for t in qp.select_topics(DATE)}
        return qp.create_draft(index,lambda f:SOURCE+' weiterer Text'*10,gen,DATE)
    def test_draft_exactly_50_and_topics_rotate(self):
        d=self.draft();self.assertEqual(len(d['quizzes']),50)
        self.assertEqual(len({q['topic'] for q in d['quizzes']}),5)
        self.assertNotEqual(qp.select_topics(DATE),qp.select_topics(DATE+dt.timedelta(days=1)))
    def test_bad_sources_duplicates_and_options_rejected(self):
        items=gen('Thema',SOURCE);items[0]['evidence']='Erfundene Quelle, die nicht im Lernzettel steht.'
        with self.assertRaises(ValueError): qp.validate_generated(items,'Thema','x.pdf',SOURCE,DATE.isoformat(),0)
        d=self.draft();d['quizzes'][1]['question']=d['quizzes'][0]['question']
        with self.assertRaises(ValueError): qp.validate_draft(d)
        d=self.draft();d['quizzes'][0]['correctIndex']=4
        with self.assertRaises(ValueError): qp.validate_draft(d)
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
