import copy
from datetime import date
import hashlib
import io
import json
import unittest
from unittest.mock import Mock
from urllib.error import HTTPError

import archive_approved_once as probe
from import_notes import TOPICS

TOKEN = 't' * 40
DAY = probe.DAY
QUESTION = {'id': 'q1', 'published': DAY, 'topic': TOPICS[0][1],
            'question': 'Test', 'options': ['A','B','C','D'], 'correctIndex': 0,
            'explanation': 'Test', 'sources': ['Test']}
CATALOG = {'schemaVersion': 1, 'revision': 7, 'notes': [], 'cards': [],
           'cases': [], 'quizzes': [QUESTION]}
RAW = json.dumps(CATALOG, ensure_ascii=False).encode()
HASH = hashlib.sha256(RAW).hexdigest()
ARCHIVE = json.dumps({'date': DAY, 'quizzes': [QUESTION]}).encode()
MISSING = b'{"error":"Quiztag nicht gefunden."}'
CONFIRM = json.dumps({'ok': True, 'date': DAY, 'questions': 1}).encode()


class Response:
    def __init__(self, status, body):
        self.status, self.body = status, body
    def __enter__(self): return self
    def __exit__(self, *args): return False
    def read(self, size=-1): return self.body[:size]


def error(code, body=b''):
    return HTTPError('https://example.invalid', code, 'error', {}, io.BytesIO(body))


def run(opener, **kwargs):
    return probe.run(TOKEN, opener, expected_hash=HASH, expected_count=1,
                     today=date(2026, 11, 24), **kwargs)


class ArchiveApprovedOnceTest(unittest.TestCase):
    def test_create_and_check_without_put(self):
        opener = Mock()
        opener.open.side_effect = [Response(200, RAW), error(404, MISSING),
                                   Response(201, CONFIRM), Response(200, ARCHIVE),
                                   Response(200, RAW)]
        self.assertEqual(run(opener), 'Archivkopie vollständig geprüft; Katalog unverändert.')
        calls = [c.args[0] for c in opener.open.call_args_list]
        self.assertEqual([c.get_method() for c in calls], ['GET', 'GET', 'POST', 'GET', 'GET'])
        self.assertEqual(calls[2].full_url, probe.BASE + '/admin/quiz-archive/' + DAY)
        self.assertEqual(calls[2].get_header('If-match'), '7')
        self.assertTrue(all(c.full_url.startswith(probe.BASE + '/') for c in calls))

    def test_hash_mismatch_stops_before_archive_or_post(self):
        opener = Mock()
        opener.open.return_value = Response(200, RAW + b' ')
        with self.assertRaises(probe.Stop): run(opener)
        self.assertEqual(opener.open.call_count, 1)

    def test_revision_and_count_mismatch_stop_before_post(self):
        for change in ('revision', 'count'):
            data = copy.deepcopy(CATALOG)
            if change == 'revision': data['revision'] = 8
            else: data['quizzes'] = []
            raw = json.dumps(data).encode()
            opener = Mock()
            opener.open.return_value = Response(200, raw)
            with self.assertRaises(probe.Stop):
                probe.run(TOKEN, opener, expected_hash=hashlib.sha256(raw).hexdigest(),
                          expected_count=1, today=date(2026, 11, 24))
            self.assertEqual(opener.open.call_count, 1)

    def test_existing_exact_archive_is_read_only(self):
        opener = Mock()
        opener.open.side_effect = [Response(200, RAW), Response(200, ARCHIVE)]
        self.assertIn('kein neuer Schreibversuch', run(opener))
        self.assertEqual(opener.open.call_count, 2)

    def test_existing_different_archive_stops(self):
        opener = Mock()
        opener.open.side_effect = [Response(200, RAW), Response(200, b'{"date":"2026-11-22","quizzes":[]}')]
        with self.assertRaises(probe.Stop): run(opener)
        self.assertEqual(opener.open.call_count, 2)

    def test_proxy_404_does_not_authorize_post(self):
        opener = Mock()
        opener.open.side_effect = [Response(200, RAW), error(404, b'not found')]
        with self.assertRaises(probe.Stop): run(opener)
        self.assertEqual(opener.open.call_count, 2)

    def test_ambiguous_post_never_retries(self):
        opener = Mock()
        opener.open.side_effect = [Response(200, RAW), error(404, MISSING), TimeoutError()]
        with self.assertRaises(probe.Stop): run(opener)
        self.assertEqual(opener.open.call_count, 3)

    def test_wrong_post_confirmation_and_changed_archive_fail_safely(self):
        for confirmation, archive in ((b'{}', ARCHIVE), (CONFIRM, b'{}')):
            opener = Mock()
            opener.open.side_effect = [Response(200, RAW), error(404, MISSING),
                                       Response(201, confirmation), Response(200, archive)]
            with self.assertRaises(probe.Stop): run(opener)
            self.assertLessEqual(opener.open.call_count, 4)

    def test_future_day_and_missing_token_never_contact_network(self):
        opener = Mock()
        with self.assertRaises(probe.Stop):
            probe.run(TOKEN, opener, expected_hash=HASH, expected_count=1, today=date(2026, 11, 22))
        with self.assertRaises(probe.Stop):
            probe.run('', opener, expected_hash=HASH, expected_count=1, today=date(2026, 11, 24))
        opener.open.assert_not_called()

    def test_redirects_disabled(self):
        self.assertIsNone(probe.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://example.invalid'))


if __name__ == '__main__':
    unittest.main()
