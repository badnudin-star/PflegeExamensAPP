import {test} from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';

test('archive migration preserves an existing snapshot and is idempotent', () => {
  const db = new DatabaseSync(':memory:');
  db.exec('CREATE TABLE content_snapshot(id INTEGER PRIMARY KEY, revision INTEGER NOT NULL, payload TEXT NOT NULL)');
  db.prepare('INSERT INTO content_snapshot VALUES(1, ?, ?)').run(7, '{"existing":"unchanged"}');
  const sql = readFileSync(new URL('../migrations/0001_quiz_archive.sql', import.meta.url), 'utf8');
  db.exec(sql);
  db.prepare('INSERT INTO quiz_archive VALUES(?, ?, ?)').run('2020-01-01', 1, 'example');
  db.exec(sql);
  assert.equal(db.prepare('SELECT payload FROM content_snapshot WHERE id=1').get().payload, '{"existing":"unchanged"}');
  assert.equal(db.prepare('SELECT payload FROM quiz_archive WHERE date=?').get('2020-01-01').payload, 'example');
});
