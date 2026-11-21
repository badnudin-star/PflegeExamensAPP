import json
import unittest
from unittest.mock import patch
from sync_drive import origin, sync, validate_base
from import_notes import expected_names

BUNDLED = {'schemaVersion':1,'revision':1,'notes':[], 'cards':[], 'cases':[], 'quizzes':[]}
FILES = {name: {'name':name} for name in expected_names()}
TOKEN = 'z' * 32

class Response:
    def __init__(self, status, body=b''): self.status_code, self.body = status, body
    def iter_content(self, _): yield self.body
    def close(self): pass

class Http:
    def __init__(self, remote=None, put_status=200):
        self.remote, self.put_status, self.puts = remote, put_status, []
    def get(self, url, **kw):
        return Response(404) if self.remote is None else Response(200,json.dumps(self.remote).encode())
    def put(self, url, **kw):
        self.puts.append((url,kw))
        return Response(self.put_status)

class SyncTests(unittest.TestCase):
    def run_sync(self, http, bundled=BUNDLED):
        with patch('sync_drive.list_folder', return_value=FILES), patch('sync_drive.download_text', side_effect=lambda _, f: f['name'] + ' Text ' * 20 + '\nQUELLEN UND GRENZEN\n'):
            return sync(http, 'https://worker.example', bundled, 'folderid', object(), TOKEN)
    def test_origin_requires_safe_https(self):
        for value in ('http://a', 'https://user:pass@a', 'https://a/path', 'https://a?x=1'):
            with self.assertRaises(ValueError): origin(value)
    def test_initial_publish_uses_bundled_data_and_cas_zero(self):
        http = Http()
        self.assertEqual(self.run_sync(http), 'published')
        _, sent = http.puts[0]
        self.assertEqual(sent['headers']['If-Match'], '0')
        c=json.loads(sent['data'])
        self.assertEqual(c['revision'],2)
        self.assertEqual(len(c['notes']),42)
    def test_server_contents_are_preserved_and_unchanged_skips_put(self):
        first=Http(); self.run_sync(first)
        remote=json.loads(first.puts[0][1]['data'])
        remote['cards']=[{'id':'1','topic':remote['notes'][0]['topic'],'front':'A','back':'B','sources':['s']}]
        http=Http(remote)
        self.assertEqual(self.run_sync(http),'unchanged')
        self.assertEqual(http.puts,[])
        remote['notes'][0]['short']='old'
        self.assertEqual(self.run_sync(http),'published')
        update=json.loads(http.puts[0][1]['data'])
        self.assertEqual(update['cards'],remote['cards'])
        self.assertEqual(update['revision'],3)
        self.assertEqual(http.puts[0][1]['headers']['If-Match'],'2')
    def test_parallel_update_and_invalid_source_abort(self):
        http=Http(None,409)
        with self.assertRaisesRegex(ValueError,'parallel'): self.run_sync(http)
        with patch('sync_drive.list_folder',return_value=FILES), patch('sync_drive.download_text',return_value='x'*100):
            http=Http()
            with self.assertRaisesRegex(ValueError,'Quellenabschnitt'): sync(http,'https://worker.example',BUNDLED,'folder',object(),TOKEN)
            self.assertEqual(http.puts,[])
    def test_folder_changed_during_import_prevents_publication(self):
        modified = dict(FILES)
        modified.pop(next(iter(modified)))
        http = Http()
        with patch('sync_drive.list_folder', side_effect=[FILES, modified]), patch('sync_drive.download_text', return_value='x'*100 + '\nQUELLEN UND GRENZEN\n'):
            with self.assertRaisesRegex(ValueError, 'während des Imports geändert'):
                sync(http, 'https://worker.example', BUNDLED, 'folder', object(), TOKEN)
        self.assertEqual(http.puts, [])
    def test_incompatible_or_stale_base_fails_before_upload(self):
        with self.assertRaises(ValueError): validate_base({'schemaVersion':1})
        http=Http(dict(BUNDLED, revision=0))
        with self.assertRaises(ValueError): self.run_sync(http)
        self.assertEqual(http.puts,[])

if __name__=='__main__': unittest.main()
