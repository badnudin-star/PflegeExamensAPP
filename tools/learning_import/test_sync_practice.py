import copy
import hashlib
import io
import unittest
from unittest.mock import patch
from sync_practice import download, import_practice, inventory, workbook

CARD = {'id': 'card-01-001', 'topic': 'Thema', 'front': 'Alt', 'back': 'Antwort', 'sources': ['Quelle']}
CASE = {'id': 'case-day-1-01', 'published': '2026-01-01', 'sources': ['Fallbeispiele/Tag_1_Fall_01_Test.pdf']}
BASE = {'cards': [CARD], 'cases': [CASE], 'quizzes': [{'id':'q'}], 'notes': [{'topic':'n'}]}
FILE = {'id':'abc', 'name':'Tag_1_Fall_01_Test.pdf', 'mimeType':'application/pdf',
        'modifiedTime':'2026-05-01T12:00:00Z', 'size':'3', 'md5Checksum':hashlib.md5(b'pdf').hexdigest()}

class Response:
    def raise_for_status(self): pass
    def iter_content(self, size): yield b'pdf'
    def close(self): pass
class Drive:
    def get(self, *args, **kwargs): return Response()

class PracticeSyncTests(unittest.TestCase):
    def test_download_checks_size_and_hash(self):
        self.assertEqual(download(Drive(), FILE), b'pdf')
        for change in ({'size':'2'}, {'md5Checksum':'0'*32}, {'size':str(9*1024*1024)}):
            with self.assertRaises(ValueError): download(Drive(), dict(FILE, **change))
    def test_inventory_rejects_missing_workbook_and_unknown_pdf(self):
        with patch('sync_practice.list_children', return_value=[]):
            with self.assertRaises(ValueError): inventory(Drive(), 'folder', 'cards')
        with patch('sync_practice.list_children', return_value=[dict(FILE, name='other.pdf')]):
            with self.assertRaises(ValueError): inventory(Drive(), 'folder', 'cases')
        with self.assertRaises(ValueError): inventory(Drive(), 'bad/folder', 'cases')
    def test_edits_additions_and_other_catalog_fields_are_preserved(self):
        updated = dict(CARD, back='Neue Antwort')
        new = dict(CARD, id='card-01-002')
        parsed = dict(CASE, published='2026-05-01', text='Neue Fallsituation')
        with patch('sync_practice.inventory', side_effect=[[{'name':'book'}], [FILE], [{'name':'book'}], [FILE]]), \
             patch('sync_practice.download', return_value=b'data'), patch('sync_practice.workbook', return_value=io.BytesIO()), \
             patch('sync_practice.read_cards', return_value=[updated, new]), \
             patch('sync_practice.extract_pdf', return_value='text'), patch('sync_practice.parse_case', return_value=parsed):
            result = import_practice(BASE, Drive(), 'cards', 'cases')
        self.assertEqual(result['cards'], [updated, new])
        self.assertEqual(result['cases'][0]['published'], CASE['published'])
        self.assertEqual(result['cases'][0]['text'], 'Neue Fallsituation')
        self.assertEqual(result['quizzes'], BASE['quizzes'])
        self.assertEqual(BASE['cards'][0]['back'], 'Antwort')
    def test_missing_or_moving_ids_and_concurrent_edits_fail(self):
        with patch('sync_practice.inventory', return_value=[{'name':'book'}]), patch('sync_practice.download', return_value=b'x'), \
             patch('sync_practice.workbook'), patch('sync_practice.read_cards', return_value=[]):
            with self.assertRaisesRegex(ValueError, 'fehlen'): import_practice(BASE, Drive(), 'cards')
        moved = dict(CASE, sources=['Fallbeispiele/anderer.pdf'])
        with patch('sync_practice.inventory', return_value=[FILE]), patch('sync_practice.download', return_value=b'x'), \
             patch('sync_practice.extract_pdf', return_value='text'), patch('sync_practice.parse_case', return_value=moved):
            with self.assertRaisesRegex(ValueError, 'andere Datei'): import_practice(BASE, Drive(), cases_folder='cases')
        with patch('sync_practice.inventory', side_effect=[[{'name':'book'}], []]), patch('sync_practice.download', return_value=b'x'), \
             patch('sync_practice.workbook'), patch('sync_practice.read_cards', return_value=[CARD]):
            with self.assertRaisesRegex(ValueError, 'geändert'): import_practice(BASE, Drive(), 'cards')
    def test_cards_changing_while_cases_are_imported_is_rejected(self):
        with patch('sync_practice.inventory', side_effect=[[{'name':'book'}], [FILE], [], [FILE]]), \
             patch('sync_practice.download', return_value=b'x'), patch('sync_practice.workbook'), \
             patch('sync_practice.read_cards', return_value=[CARD]), \
             patch('sync_practice.extract_pdf', return_value='text'), patch('sync_practice.parse_case', return_value=copy.deepcopy(CASE)):
            with self.assertRaisesRegex(ValueError, 'geändert'):
                import_practice(BASE, Drive(), 'cards', 'cases')

    def test_duplicate_case_id_is_not_published(self):
        with patch('sync_practice.inventory', return_value=[FILE, dict(FILE, id='another')]), \
             patch('sync_practice.download', return_value=b'x'), patch('sync_practice.extract_pdf', return_value='text'), \
             patch('sync_practice.parse_case', side_effect=lambda *args: copy.deepcopy(CASE)):
            with self.assertRaisesRegex(ValueError, 'Doppelte'): import_practice(BASE, Drive(), cases_folder='cases')
    def test_bad_workbook_rejected(self):
        with self.assertRaises(ValueError): workbook(b'not a zip')

if __name__ == '__main__': unittest.main()
