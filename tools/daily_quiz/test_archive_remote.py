import copy
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from archive_remote import operate
from quiz_archive import create
from import_notes import TOPICS

DAY = '2020-01-01'
TOKEN = 't' * 32
QUESTION = {'id':'q1', 'published':DAY, 'topic':TOPICS[0][1], 'question':'Testfrage',
            'options':['A','B','C','D'], 'correctIndex':1, 'explanation':'Testerklärung', 'sources':['Test']}
SNAPSHOT = {'schemaVersion':1, 'revision':2, 'notes':[], 'cards':[], 'cases':[], 'quizzes':[QUESTION]}

class Response:
    def __init__(self, code, value=None): self.status_code, self.value = code, value
    def iter_content(self, _): yield json.dumps(self.value, ensure_ascii=False).encode()
    def close(self): pass

class Http:
    def __init__(self):
        self.catalog = copy.deepcopy(SNAPSHOT)
        self.archives = {}
        self.calls = []
        self.race = False
    def get(self, url, **kwargs):
        self.calls.append(('GET', url))
        if url.endswith('/content'): return Response(200, self.catalog)
        day = url.rsplit('/', 1)[-1]
        if self.race: self.catalog['revision'] += 1
        return Response(200, self.archives[day]) if day in self.archives else Response(404)
    def post(self, url, headers, **kwargs):
        self.calls.append(('POST', url))
        if headers['If-Match'] != str(self.catalog['revision']): return Response(409)
        day = url.rsplit('/', 1)[-1]
        value = {'date':day, 'quizzes':[q for q in self.catalog['quizzes'] if q['published'] == day]}
        if day in self.archives and self.archives[day] != value: return Response(409)
        self.archives[day] = copy.deepcopy(value)
        return Response(201)
    def put(self, url, data, headers, **kwargs):
        self.calls.append(('PUT', url))
        if headers['If-Match'] != str(self.catalog['revision']): return Response(409)
        self.catalog = json.loads(data)
        return Response(200)

class ArchiveRemoteTest(unittest.TestCase):
    def setUp(self):
        summary = patch.dict(os.environ, {'GITHUB_STEP_SUMMARY':''})
        summary.start()
        self.addCleanup(summary.stop)
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.backup = Path(self.dir.name) / 'archive.zip'
        self.raw = json.dumps(SNAPSHOT, ensure_ascii=False).encode()
        create(self.raw, self.backup)
        self.http = Http()

    def run_op(self, mode, **kwargs):
        return operate(self.http, 'https://example.test', TOKEN, self.raw, self.backup, DAY, mode, **kwargs)

    def test_copy_then_explicit_prune_keeps_exact_archive_and_other_content(self):
        self.assertEqual(1, self.run_op('copy'))
        self.assertEqual(SNAPSHOT, self.http.catalog)
        self.assertEqual({'date':DAY,'quizzes':[QUESTION]}, self.http.archives[DAY])
        with self.assertRaises(ValueError): self.run_op('prune')
        self.assertEqual(1, self.run_op('prune', confirm_compatibility=True))
        self.assertEqual([], self.http.catalog['quizzes'])
        self.assertEqual(3, self.http.catalog['revision'])
        for key in ('notes','cards','cases'): self.assertEqual(SNAPSHOT[key], self.http.catalog[key])
        self.assertEqual({'date':DAY,'quizzes':[QUESTION]}, self.http.archives[DAY])

    def test_conflicting_missing_or_tampered_data_never_prunes(self):
        with self.assertRaises(ValueError): self.run_op('prune', confirm_compatibility=True)
        self.assertFalse(any(call[0] == 'PUT' for call in self.http.calls))
        self.run_op('copy')
        self.http.archives[DAY]['quizzes'][0]['question'] = 'Geändert'
        with self.assertRaises(ValueError): self.run_op('prune', confirm_compatibility=True)
        self.assertEqual(SNAPSHOT, self.http.catalog)
        self.http.catalog['revision'] += 1
        with self.assertRaises(ValueError): self.run_op('copy')
        self.assertFalse(any(call[0] == 'PUT' for call in self.http.calls))

    def test_race_fails_cas_and_bad_backup_never_contacts_network(self):
        self.run_op('copy')
        self.http.race = True
        with self.assertRaises(ValueError): self.run_op('prune', confirm_compatibility=True)
        self.assertEqual([QUESTION], self.http.catalog['quizzes'])
        self.http.calls.clear()
        self.backup.write_bytes(b'bad zip')
        with self.assertRaises(ValueError): self.run_op('copy')
        self.assertEqual([], self.http.calls)

if __name__ == '__main__': unittest.main()
