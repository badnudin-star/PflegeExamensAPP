import json
import unittest
from unittest.mock import patch
from sync_drive import origin, sync, validate_base, read_remote
from import_notes import TOPICS, topic_candidates

BUNDLED = {'schemaVersion':1,'revision':1,'notes':[], 'cards':[], 'cases':[], 'quizzes':[]}
FILES = {name: {'name':name} for n in range(len(TOPICS)) for name in topic_candidates(n)[0]}
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
    def setUp(self):
        # Synthetic catalog sizes must never be written to a real Actions summary.
        env = patch.dict('os.environ', {'GITHUB_STEP_SUMMARY': ''})
        env.start()
        self.addCleanup(env.stop)

    def run_sync(self, http, bundled=BUNDLED):
        with patch('sync_drive.list_folder', return_value=FILES), patch('sync_drive.download_text', side_effect=lambda _, f: f['name'] + ' Text ' * 20 + '\nQUELLEN UND GRENZEN\n'):
            return sync(http, 'https://worker.example', bundled, 'folderid', object(), TOKEN)
    def test_remote_size_uses_actual_downloaded_bytes_without_extra_request(self):
        http = Http(BUNDLED)
        with patch('sync_drive.report_capacity') as report:
            self.assertEqual(read_remote(http, 'https://worker.example'), BUNDLED)
            report.assert_called_once_with(len(json.dumps(BUNDLED).encode()), 'server')
        with patch('sync_drive.report_capacity') as report:
            self.assertIsNone(read_remote(Http(), 'https://worker.example'))
            report.assert_not_called()

    def test_retired_ids_are_validated_and_cannot_overlap_active_cards(self):
        base = dict(BUNDLED, cards=[{'id':'card-a', 'topic':TOPICS[0][1], 'front':'Q', 'back':'A', 'sources':['S']}])
        validate_base(dict(base, retiredCardIds=['card-old']))
        for retired in (['card-a'], ['card-old', 'card-old'], [''], 'card-old'):
            with self.subTest(retired=retired), self.assertRaises(ValueError):
                validate_base(dict(base, retiredCardIds=retired))

    def test_origin_requires_safe_https(self):
        for value in ('http://a', 'https://user:pass@a', 'https://a/path', 'https://a?x=1'):
            with self.assertRaises(ValueError): origin(value)
    def test_notes_candidate_report_matches_exact_payload_sent(self):
        http = Http()
        with patch('sync_drive.report_capacity') as report:
            self.assertEqual(self.run_sync(http), 'published')
            report.assert_called_once_with(len(http.puts[0][1]['data']), 'notes_candidate')

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
    def test_same_drive_filename_with_changed_text_publishes_new_revision(self):
        filename = topic_candidates(0)[0][0]
        def extract(text):
            return lambda _, f: f['name'] + (' ' + text if f['name'] == filename else '') + ' Text ' * 20 + '\nQUELLEN UND GRENZEN\n'
        # Keep every Drive file ID and filename unchanged; only its contents change.
        with patch('sync_drive.list_folder', return_value=FILES), patch('sync_drive.download_text', side_effect=extract('alt')):
            initial = Http()
            self.assertEqual(sync(initial, 'https://worker.example', BUNDLED, 'folderid', object(), TOKEN), 'published')
        remote = json.loads(initial.puts[0][1]['data'])
        with patch('sync_drive.list_folder', return_value=FILES), patch('sync_drive.download_text', side_effect=extract('neu')):
            http = Http(remote)
            self.assertEqual(sync(http, 'https://worker.example', BUNDLED, 'folderid', object(), TOKEN), 'published')
        updated = json.loads(http.puts[0][1]['data'])
        self.assertEqual(updated['revision'], remote['revision'] + 1)
        self.assertEqual(updated['notes'][0]['sources'], remote['notes'][0]['sources'])
        self.assertIn(' neu ', updated['notes'][0]['detailed'])
        self.assertEqual(http.puts[0][1]['headers']['If-Match'], str(remote['revision']))
        self.assertEqual(updated['cards'], remote['cards'])
        self.assertEqual(updated['cases'], remote['cases'])
        self.assertEqual(updated['quizzes'], remote['quizzes'])

    def test_note_only_publish_preserves_existing_practice_and_quizzes(self):
        first = Http(); self.run_sync(first)
        remote = json.loads(first.puts[0][1]['data'])
        topic = remote['notes'][0]['topic']
        remote['cards'] = [{'id':'card-old', 'topic':topic, 'front':'Q', 'back':'A', 'sources':['S']}]
        remote['cases'] = [{'id':'case-old', 'published':'2026-01-01', 'text':'T', 'sources':['S'], 'diseases':['D'], 'questions':[{'question':'Q', 'answer':'A', 'sources':['S']}]}]
        remote['quizzes'] = [{'id':'quiz-old', 'topic':topic, 'published':'2026-01-01', 'question':'Q', 'explanation':'E', 'options':['a','b','c','d'], 'correctIndex':0, 'sources':['S']}]
        remote['notes'][0]['short'] = 'alter Text'
        http = Http(remote)
        with patch('sync_drive.import_practice', side_effect=lambda base, drive, cards, cases: base) as practice:
            self.assertEqual(self.run_sync(http), 'published')
        self.assertEqual(practice.call_args.args[2:], ('', ''))
        published = json.loads(http.puts[0][1]['data'])
        for section in ('cards', 'cases', 'quizzes'):
            self.assertEqual(published[section], remote[section])
        self.assertNotEqual(published['notes'][0]['short'], 'alter Text')

    def test_workflow_syncs_notes_before_full_practice_without_practice_folders(self):
        from pathlib import Path
        workflow = (Path(__file__).resolve().parents[2] / '.github/workflows/sync-learning-content.yml').read_text()
        part = workflow.split('Lernzettel unabhängig von Übungsdateien veröffentlichen', 1)[1].split('Drive prüfen und Katalog veröffentlichen', 1)[0]
        self.assertIn("PRACTICE_CARDS_FOLDER_ID: ''", part)
        self.assertIn("PRACTICE_CASES_FOLDER_ID: ''", part)
        self.assertIn('tools/learning_import/sync_drive.py', part)

    def test_revised_cards_are_enabled_only_in_practice_step(self):
        from pathlib import Path
        workflow = (Path(__file__).resolve().parents[2] / '.github/workflows/sync-learning-content.yml').read_text()
        notes = workflow.split('Lernzettel unabhängig von Übungsdateien veröffentlichen', 1)[1].split('Drive prüfen und Katalog veröffentlichen', 1)[0]
        practice = workflow.split('Drive prüfen und Katalog veröffentlichen', 1)[1]
        self.assertNotIn('PRACTICE_CARD_FORMAT: revised', notes)
        self.assertIn('PRACTICE_CARD_FORMAT: revised', practice)

    def test_practice_only_change_publishes_and_preserves_other_sections(self):
        first = Http(); self.run_sync(first)
        remote = json.loads(first.puts[0][1]['data'])
        card = {'id':'card-01-001', 'topic':remote['notes'][0]['topic'], 'front':'Frage', 'back':'Antwort', 'sources':['Quelle']}
        remote['cards'] = [card]
        http = Http(remote)
        def update(candidate, drive, cards_folder, cases_folder):
            self.assertEqual(cards_folder, 'cards')
            candidate['cards'] = [dict(card, back='Korrigiert')]
            return candidate
        with patch('sync_drive.list_folder', return_value=FILES), patch('sync_drive.download_text', side_effect=lambda _, f: f['name'] + ' Text ' * 20 + '\nQUELLEN UND GRENZEN\n'), patch('sync_drive.import_practice', side_effect=update):
            self.assertEqual(sync(http, 'https://worker.example', BUNDLED, 'folder', object(), TOKEN, 'cards'), 'published')
        sent = json.loads(http.puts[0][1]['data'])
        self.assertEqual(sent['cards'][0]['back'], 'Korrigiert')
        self.assertEqual(sent['quizzes'], remote['quizzes'])
        self.assertEqual(sent['revision'], remote['revision'] + 1)
        self.assertEqual(http.puts[0][1]['headers']['If-Match'], str(remote['revision']))

    def test_read_only_check_exercises_import_without_put_for_changes_or_no_changes(self):
        http = Http()
        with patch('sync_drive.list_folder', return_value=FILES), patch('sync_drive.download_text', side_effect=lambda _, f: f['name'] + ' Text ' * 20 + '\nQUELLEN UND GRENZEN\n'):
            result = sync(http, 'https://worker.example', BUNDLED, 'folder', object(), TOKEN, check_only=True)
        self.assertEqual(result, 'checked-changed')
        self.assertEqual(http.puts, [])
        first = Http(); self.run_sync(first)
        remote = json.loads(first.puts[0][1]['data'])
        http = Http(remote)
        with patch('sync_drive.list_folder', return_value=FILES), patch('sync_drive.download_text', side_effect=lambda _, f: f['name'] + ' Text ' * 20 + '\nQUELLEN UND GRENZEN\n'):
            result = sync(http, 'https://worker.example', BUNDLED, 'folder', object(), TOKEN, check_only=True)
        self.assertEqual(result, 'checked-unchanged')
        self.assertEqual(http.puts, [])

    def test_parallel_update_and_invalid_source_abort(self):
        http=Http(None,409)
        with self.assertRaisesRegex(ValueError,'parallel'): self.run_sync(http)
        with patch('sync_drive.list_folder',return_value=FILES), patch('sync_drive.download_text',return_value='x'*100):
            http=Http()
            with self.assertRaisesRegex(ValueError,'Quellenabschnitt'): sync(http,'https://worker.example',BUNDLED,'folder',object(),TOKEN)
            self.assertEqual(http.puts,[])
    def test_source_section_can_describe_open_gaps(self):
        http = Http()
        with patch('sync_drive.list_folder', return_value=FILES), patch('sync_drive.download_text', return_value='x'*100 + '\nQUELLEN UND OFFENE LÜCKE\n'):
            self.assertEqual(sync(http, 'https://worker.example', BUNDLED, 'folder', object(), TOKEN), 'published')
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
