import sqlite3
import unittest
from pathlib import Path


class QuizSchemaTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.executescript((Path(__file__).resolve().parents[1] / 'schema.sql').read_text())
        self.db.execute('CREATE TABLE scores (user_id TEXT, total_xp INTEGER)')
        self.db.execute('INSERT INTO scores VALUES (?, ?)', ('legacy', 500000))
        self.db.execute('INSERT INTO quiz_participants VALUES (?, ?, ?, ?)', ('p1', 'hash1', 'Name', 1))

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


if __name__ == '__main__':
    unittest.main()
