import sqlite3
import unittest
from pathlib import Path


class QuizSchemaTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.execute('PRAGMA foreign_keys = ON')
        self.db.executescript((Path(__file__).resolve().parents[1] / 'schema.sql').read_text())
        self.db.execute('CREATE TABLE scores (user_id TEXT, total_xp INTEGER)')
        self.db.execute('INSERT INTO scores VALUES (?, ?)', ('legacy', 500000))
        self.db.execute('INSERT INTO quiz_participants VALUES (?, ?, ?, ?)', ('p1', 'hash1', 'Name', 1))

    def test_payload_hash_validation_and_old_database_migration(self):
        sql = 'INSERT INTO quiz_attempts(participant_id,quiz_date,correct,total,submitted_at,payload_hash) VALUES(?,?,?,?,?,?)'
        self.db.execute(sql, ('p1', '2026-01-01', 1, 50, 1, 'a' * 64))
        for value in ('b' * 63, 'g' * 64, 'A' * 64):
            with self.assertRaises(sqlite3.IntegrityError):
                self.db.execute(sql, ('p1', '2026-01-02', 1, 50, 1, value))
        old = sqlite3.connect(':memory:')
        old.execute('CREATE TABLE quiz_attempts(participant_id TEXT, quiz_date TEXT, correct INTEGER, total INTEGER, submitted_at INTEGER)')
        old.execute("INSERT INTO quiz_attempts VALUES('p1','2026-01-01',1,50,1)")
        old.executescript((Path(__file__).resolve().parents[1] / 'migrations/0001_attempt_payload_hash.sql').read_text())
        self.assertIsNone(old.execute('SELECT payload_hash FROM quiz_attempts').fetchone()[0])
        with self.assertRaises(sqlite3.IntegrityError):
            old.execute("UPDATE quiz_attempts SET payload_hash = 'bad'")
        old.close()

    def test_closure_migration_preserves_existing_attempts_and_is_idempotent(self):
        self.db.execute('INSERT INTO quiz_attempts(participant_id,quiz_date,correct,total,submitted_at) VALUES(?,?,?,?,?)',
                        ('p1', '2026-11-01', 25, 50, 1))
        self.db.execute('DROP TABLE quiz_attempt_closures')
        migration = (Path(__file__).resolve().parents[1] / 'migrations/0002_attempt_closures.sql').read_text()
        self.db.executescript(migration)
        self.db.executescript(migration)
        self.assertEqual(self.db.execute('SELECT correct FROM quiz_attempts').fetchone()[0], 25)
        self.db.execute('INSERT INTO quiz_attempt_closures VALUES(?,?,?)', ('p1', '2026-11-02', 2))
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute('INSERT INTO quiz_attempt_closures VALUES(?,?,?)', ('p1', '2026-11-02', 3))
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute('INSERT INTO quiz_attempt_closures VALUES(?,?,?)', ('unknown', '2026-11-02', 3))
        # Even a legacy worker using plain VALUES cannot bypass a confirmed closure.
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute('INSERT INTO quiz_attempts(participant_id,quiz_date,correct,total,submitted_at) VALUES(?,?,?,?,?)',
                            ('p1', '2026-11-02', 50, 50, 3))
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute('INSERT INTO quiz_attempt_closures VALUES(?,?,?)', ('p1', '2026-11-01', 4))

    def test_deletion_migration_blocks_legacy_registration_and_rolls_back_with_delete(self):
        self.db.execute('DROP TRIGGER quiz_participant_delete_barrier')
        self.db.execute('DROP TRIGGER quiz_participant_reject_deleted')
        self.db.execute('DROP TABLE quiz_deleted_tokens')
        migration = (Path(__file__).resolve().parents[1] / 'migrations/0003_deletion_barrier.sql').read_text()
        self.db.executescript(migration)
        self.db.executescript(migration)
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db:
                self.db.execute('DELETE FROM quiz_participants WHERE id=?', ('p1',))
                self.db.execute('UPDATE quiz_snapshot_generation SET generation=-1')
        self.assertEqual(self.db.execute('SELECT count(*) FROM quiz_deleted_tokens').fetchone()[0], 0)
        self.assertEqual(self.db.execute('SELECT count(*) FROM quiz_participants').fetchone()[0], 1)
        self.db.execute('DELETE FROM quiz_participants WHERE id=?', ('p1',))
        barrier = self.db.execute('SELECT token_hash, deleted_at FROM quiz_deleted_tokens').fetchone()
        self.assertEqual(barrier[0], 'hash1')
        self.assertGreater(barrier[1], 0)
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute('INSERT INTO quiz_participants VALUES(?,?,?,?)', ('p2', 'hash1', 'Again', 2))
        self.db.execute('INSERT INTO quiz_participants VALUES(?,?,?,?)', ('p2', 'different', 'Other', 2))

    def test_retry_registration_by_token_is_idempotent_and_never_renames(self):
        sql = ('INSERT INTO quiz_participants(id, token_hash, username, created_at) VALUES (?, ?, ?, ?) '
               'ON CONFLICT(token_hash) DO NOTHING')
        self.assertEqual(self.db.execute(sql, ('p2', 'hash2', 'Neuer Name', 2)).rowcount, 1)
        self.assertEqual(self.db.execute(sql, ('p3', 'hash2', 'Anderer Name', 3)).rowcount, 0)
        self.assertEqual(self.db.execute('SELECT username FROM quiz_participants WHERE token_hash=?', ('hash2',)).fetchone()[0], 'Neuer Name')
        self.assertEqual(self.db.execute('SELECT count(*) FROM quiz_participants').fetchone()[0], 2)

    def test_result_query_uses_both_account_and_quiz_date(self):
        self.db.execute('INSERT INTO quiz_participants VALUES (?, ?, ?, ?)', ('p2', 'hash2', 'Other', 2))
        self.db.execute('INSERT INTO quiz_attempts(participant_id, quiz_date, correct, total, submitted_at) VALUES (?, ?, ?, ?, ?)', ('p1', '2026-11-01', 10, 50, 1))
        self.db.execute('INSERT INTO quiz_attempts(participant_id, quiz_date, correct, total, submitted_at) VALUES (?, ?, ?, ?, ?)', ('p2', '2026-11-01', 40, 50, 2))
        query = 'SELECT correct, total FROM quiz_attempts WHERE participant_id = ? AND quiz_date = ?'
        self.assertEqual(self.db.execute(query, ('p1', '2026-11-01')).fetchone(), (10, 50))
        self.assertEqual(self.db.execute(query, ('p2', '2026-11-01')).fetchone(), (40, 50))
        self.assertIsNone(self.db.execute(query, ('p1', '2026-11-02')).fetchone())

    def test_generation_blocks_snapshot_that_started_before_deletion(self):
        self.db.execute('INSERT INTO quiz_attempts(participant_id, quiz_date, correct, total, submitted_at) VALUES (?, ?, ?, ?, ?)', ('p1', '2026-11-01', 10, 50, 1))
        self.db.execute('INSERT INTO quiz_hourly_snapshot VALUES (1, ?, ?)', ('old', 1))
        generation = self.db.execute('SELECT generation FROM quiz_snapshot_generation WHERE id=1').fetchone()[0]
        with self.db:
            self.db.execute('DELETE FROM quiz_attempts WHERE participant_id = ?', ('p1',))
            self.db.execute('DELETE FROM quiz_participants WHERE id = ?', ('p1',))
            self.db.execute('DELETE FROM quiz_hourly_snapshot WHERE id = 1')
            self.db.execute('UPDATE quiz_snapshot_generation SET generation = generation + 1 WHERE id = 1')
        write = ("INSERT INTO quiz_hourly_snapshot(id, payload_json, generated_at) "
                 "SELECT 1, ?, ? WHERE (SELECT generation FROM quiz_snapshot_generation WHERE id = 1) = ? "
                 "ON CONFLICT(id) DO UPDATE SET payload_json = excluded.payload_json, generated_at = excluded.generated_at")
        self.assertEqual(self.db.execute(write, ('stale', 3, generation)).rowcount, 0)
        self.assertIsNone(self.db.execute('SELECT * FROM quiz_hourly_snapshot').fetchone())
        self.assertEqual(self.db.execute(write, ('new', 4, generation + 1)).rowcount, 1)
        self.assertEqual(self.db.execute('SELECT payload_json FROM quiz_hourly_snapshot').fetchone()[0], 'new')
        self.assertEqual(self.db.execute('SELECT count(*) FROM quiz_attempts').fetchone()[0], 0)
        self.assertEqual(self.db.execute('SELECT count(*) FROM quiz_participants').fetchone()[0], 0)
        self.assertEqual(self.db.execute('SELECT total_xp FROM scores').fetchone()[0], 500000)

    def test_deletion_transaction_rolls_back_if_generation_update_fails(self):
        self.db.commit()
        self.db.execute('INSERT INTO quiz_attempts(participant_id, quiz_date, correct, total, submitted_at) VALUES (?, ?, ?, ?, ?)', ('p1', '2026-11-01', 10, 50, 1))
        self.db.commit()
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db:
                self.db.execute('DELETE FROM quiz_attempts WHERE participant_id = ?', ('p1',))
                self.db.execute('DELETE FROM quiz_participants WHERE id = ?', ('p1',))
                self.db.execute('UPDATE quiz_snapshot_generation SET generation = -1 WHERE id = 1')
        self.assertEqual(self.db.execute('SELECT count(*) FROM quiz_attempts').fetchone()[0], 1)
        self.assertEqual(self.db.execute('SELECT count(*) FROM quiz_participants').fetchone()[0], 1)

    def test_one_attempt_per_date_and_no_legacy_points(self):
        insert = ('INSERT INTO quiz_attempts(participant_id, quiz_date, correct, total, submitted_at) '
                  'VALUES(?, ?, ?, ?, ?) ON CONFLICT(participant_id, quiz_date) DO NOTHING')
        self.assertEqual(self.db.execute(insert, ('p1', '2026-11-01', 20, 50, 1)).rowcount, 1)
        self.assertEqual(self.db.execute(insert, ('p1', '2026-11-01', 50, 50, 2)).rowcount, 0)
        self.assertEqual(self.db.execute(insert, ('p1', '2026-11-02', 30, 50, 3)).rowcount, 1)
        self.assertEqual(self.db.execute('SELECT SUM(correct) FROM quiz_attempts').fetchone()[0], 50)
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute(insert, ('p1', '2026-11-03', 51, 50, 3))
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute(insert, ('p1', '2026-11-03', 1, 4, 3))


class RateLimitConfigTests(unittest.TestCase):
    def test_config_binds_all_required_limiters_with_distinct_namespaces(self):
        import tomllib
        path = Path(__file__).resolve().parents[1] / 'wrangler.toml'
        config = tomllib.loads(path.read_text())
        rate = config['ratelimits']
        self.assertEqual({item['name'] for item in rate},
                         {'REGISTRATION_RATE', 'READ_RATE', 'AUTH_RATE', 'ACCOUNT_RATE'})
        self.assertEqual(len({item['namespace_id'] for item in rate}), 4)
        self.assertTrue(all(item['namespace_id'].isdigit() and int(item['namespace_id']) > 0 for item in rate))
        self.assertTrue(all(item['simple']['period'] in (10, 60) and item['simple']['limit'] > 0 for item in rate))


if __name__ == '__main__':
    unittest.main()
