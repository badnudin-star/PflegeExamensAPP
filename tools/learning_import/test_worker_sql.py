import re
import sqlite3
import unittest
from pathlib import Path

class WorkerCompareAndSwapTest(unittest.TestCase):
    def test_real_sql_rejects_stale_or_absent_preconditions(self):
        root = Path(__file__).resolve().parents[2]
        js = (root / 'cloudflare-content/src/worker.js').read_text()
        query = re.search(r'prepare\(`(INSERT INTO content_snapshot .*?)`\)', js, re.S).group(1)
        db = sqlite3.connect(':memory:')
        db.executescript((root / 'cloudflare-content/schema.sql').read_text())
        def insert(revision, expected):
            return db.execute(query,(revision,str(revision),expected,expected,expected)).rowcount
        self.assertEqual(insert(2,1),0)   # no row, but not expecting empty
        self.assertEqual(insert(2,0),1)   # first publication
        self.assertEqual(insert(3,0),0)   # another first publication is stale
        self.assertEqual(insert(3,2),1)   # advance from the observed revision
        self.assertEqual(insert(4,2),0)   # stale publisher cannot overwrite it
        self.assertEqual(db.execute('select revision,payload from content_snapshot').fetchone(),(3,'3'))

if __name__ == '__main__': unittest.main()
