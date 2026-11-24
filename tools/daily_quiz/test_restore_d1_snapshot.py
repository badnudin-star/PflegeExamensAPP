import copy
import io
import json
from pathlib import Path
import sqlite3
import unittest
from unittest.mock import Mock

import restore_d1_snapshot as restore

DAY = '2026-11-22'
SRC_ID = '80458a91-b0b0-49a9-8937-cccc7f6d9be3'
DST_ID = '755db2dc-85bf-447c-ba83-cef4ca770486'
DST_NAME = 'pflegeexamen-archiv-sicherung-restore'
QUESTIONS = [{'id': f'q{i}', 'published': DAY, 'question': 'Ä' * 2000 + "'x"}
             for i in range(50)]
CATALOG = json.dumps({'schemaVersion': 1, 'revision': 7, 'quizzes': QUESTIONS}, ensure_ascii=False)
ARCHIVE = json.dumps({'date': DAY, 'quizzes': QUESTIONS}, ensure_ascii=False)
SCHEMA = (Path(__file__).resolve().parents[2] / 'cloudflare-content' / 'schema.sql').read_text()


def dump(catalog=CATALOG, archive=ARCHIVE):
    db = sqlite3.connect(':memory:')
    try:
        db.executescript(SCHEMA)
        db.execute('INSERT INTO content_snapshot VALUES (1, 7, ?)', [catalog])
        db.execute('INSERT INTO quiz_archive VALUES (?, 50, ?)', [DAY, archive])
        db.commit()
        return '\n'.join(db.iterdump())
    finally:
        db.close()


class FakeD1:
    def __init__(self):
        self.db = sqlite3.connect(':memory:')
        self.calls = []
        self.infos = {
            SRC_ID: {'uuid': SRC_ID, 'name': restore.SOURCE_NAME},
            DST_ID: {'uuid': DST_ID, 'name': DST_NAME},
        }
    def info(self, db_id):
        self.calls.append(('info', db_id))
        return self.infos[db_id]
    def query(self, db_id, sql, params=None):
        self.calls.append((db_id, sql, params))
        assert db_id == DST_ID, 'A reconstruction query must never touch the source'
        before = self.db.total_changes
        cur = self.db.execute(sql, params or [])
        result = []
        if cur.description:
            names = [column[0] for column in cur.description]
            result = [dict(zip(names, row)) for row in cur.fetchall()]
        return {'success': True, 'results': result,
                'meta': {'changes': self.db.total_changes - before}}


class RestoreSnapshotTest(unittest.TestCase):
    def setUp(self):
        self.plan = restore.plan_from_sql(dump(), 7, DAY, 50)
        self.d1 = FakeD1()
        self.addCleanup(self.d1.db.close)

    def test_two_tables_reconstructed_and_all_rows_compared(self):
        restore.verify_identity(self.d1, SRC_ID, DST_ID, DST_NAME)
        progress = []
        restore.reconstruct(self.d1, DST_ID, self.plan, lambda *args: progress.append(args))
        self.assertEqual(restore.destination_schema(self.d1, DST_ID), self.plan['schema'])
        self.assertEqual(self.d1.db.execute(restore.SNAPSHOT_QUERY).fetchall(), self.plan['snapshot'])
        self.assertEqual(self.d1.db.execute(restore.ARCHIVE_QUERY).fetchall(), self.plan['archive'])
        self.assertTrue(any(table == 'content_snapshot' and total > 1 for table, _, total in progress))
        self.assertTrue(any(table == 'quiz_archive' and total > 1 for table, _, total in progress))
        self.assertFalse(any(call[0] == SRC_ID for call in self.d1.calls))
        updates = [call for call in self.d1.calls if len(call) == 3 and call[1].startswith('UPDATE')]
        self.assertTrue(all(len(call[2][0]) <= restore.CHUNK_CHARS for call in updates))

    def test_same_or_wrong_identity_prevents_write(self):
        for source, target, name in ((SRC_ID, SRC_ID, DST_NAME), (SRC_ID, DST_ID, 'wrong-restore'),
                                     (SRC_ID, DST_ID, restore.SOURCE_NAME)):
            with self.assertRaises(restore.RestoreError):
                restore.verify_identity(self.d1, source, target, name)
        self.assertFalse(any(len(call) == 3 for call in self.d1.calls))

    def test_nonempty_target_blocks_before_any_write(self):
        self.d1.db.execute('CREATE TABLE already_here (id INTEGER)')
        with self.assertRaises(restore.RestoreError):
            restore.reconstruct(self.d1, DST_ID, self.plan)
        writes = [c for c in self.d1.calls if len(c) == 3 and not c[1].startswith('SELECT')]
        self.assertEqual(writes, [])

    def test_mismatched_archive_fails_before_network(self):
        bad = json.dumps({'date': DAY, 'quizzes': QUESTIONS[:-1]}, ensure_ascii=False)
        with self.assertRaises(restore.RestoreError):
            restore.plan_from_sql(dump(archive=bad), 7, DAY, 50)
        self.assertEqual(self.d1.calls, [])

    def test_revision_count_and_extra_schema_fail_closed(self):
        for sql, revision, count in ((dump(), 8, 50), (dump(), 7, 49),
                                     (dump() + '\nCREATE TABLE extra (x TEXT);', 7, 50)):
            with self.assertRaises(restore.RestoreError):
                restore.plan_from_sql(sql, revision, DAY, count)
        self.assertEqual(self.d1.calls, [])

    def test_duplicate_json_is_rejected(self):
        bad = '{"revision":7,"revision":7,"quizzes":[]}'
        with self.assertRaises(ValueError):
            restore.plan_from_sql(dump(catalog=bad), 7, DAY, 50)

    def test_db_attach_and_temp_store_changes_are_rejected(self):
        for statement in ("ATTACH DATABASE ':memory:' AS extra;", 'PRAGMA temp_store=FILE;'):
            with self.assertRaises(sqlite3.DatabaseError):
                restore.plan_from_sql(dump() + '\n' + statement, 7, DAY, 50)

    def test_failed_update_stops_and_no_retry(self):
        original = self.d1.query
        def fail_on_update(db_id, sql, params=None):
            if sql.startswith('UPDATE'):
                raise TimeoutError('untrusted detail')
            return original(db_id, sql, params)
        self.d1.query = Mock(side_effect=fail_on_update)
        with self.assertRaises(TimeoutError):
            restore.reconstruct(self.d1, DST_ID, self.plan)
        self.assertEqual(sum(call.args[1].startswith('UPDATE') for call in self.d1.query.call_args_list), 1)

    def test_corrupted_readback_is_detected(self):
        original = self.d1.query
        def corrupt(db_id, sql, params=None):
            result = original(db_id, sql, params)
            if sql == restore.ARCHIVE_QUERY and result['results']:
                result['results'][0]['payload'] = 'changed'
            return result
        self.d1.query = Mock(side_effect=corrupt)
        with self.assertRaises(restore.RestoreError):
            restore.reconstruct(self.d1, DST_ID, self.plan)

    def test_redirects_are_never_followed(self):
        self.assertIsNone(restore.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://example.invalid'))


if __name__ == '__main__':
    unittest.main()
