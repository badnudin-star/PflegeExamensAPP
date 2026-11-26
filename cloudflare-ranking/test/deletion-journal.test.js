import {test} from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import {recordDeletion} from '../src/deletion-journal.js';

function fixture() {
  const db = new DatabaseSync(':memory:');
  db.exec(readFileSync(new URL('../journal-schema.sql', import.meta.url), 'utf8'));
  return {db, d1:{prepare(sql) {
    let args = [];
    return {bind(...values) {args = values; return this;},
      async run() {db.prepare(sql).run(...args); return {success:true};},
      async first() {return db.prepare(sql).get(...args) ?? null;}};
  }}};
}
const hash = 'ab'.repeat(32);

test('journal is append-only, idempotent and rejects invalid hashes', async () => {
  const {db, d1} = fixture();
  try {
    assert.equal(await recordDeletion(d1, hash, 100), 100);
    assert.equal(await recordDeletion(d1, hash, 200), 100);
    db.prepare('INSERT OR REPLACE INTO deletion_journal VALUES (?, ?)').run(hash, 999);
    assert.equal(db.prepare('SELECT recorded_at FROM deletion_journal').get().recorded_at, 100);
    assert.throws(() => db.prepare('UPDATE deletion_journal SET recorded_at = 999').run());
    assert.throws(() => db.prepare('DELETE FROM deletion_journal').run());
    await assert.rejects(recordDeletion(d1, 'a'.repeat(63), 1));
    await assert.rejects(recordDeletion(d1, 'AB'.repeat(32), 1));
    await assert.rejects(recordDeletion(d1, hash, -1));
    await assert.rejects(recordDeletion(d1, hash, Number.MAX_SAFE_INTEGER + 1));
    assert.equal(db.prepare('SELECT count(*) AS n FROM deletion_journal').get().n, 1);
  } finally { db.close(); }
});

test('unavailable, unconfirmed, or inconsistent journal fails closed', async () => {
  await assert.rejects(recordDeletion(null, hash, 1));
  const writer = run => ({prepare() {return {bind() {return this;}, run};}});
  await assert.rejects(recordDeletion(writer(async () => {throw Error('unavailable');}), hash, 1));
  await assert.rejects(recordDeletion(writer(async () => ({success:false})), hash, 1));
  const broken = {prepare(sql) {return {bind() {return this;},
    async run() {return {success:true};}, async first() {return sql.startsWith('SELECT') ? null : {};}};}};
  await assert.rejects(recordDeletion(broken, hash, 1));
  const inconsistent = {prepare() {return {bind() {return this;}, async run() {return {success:true};},
    async first() {return {token_hash:'cd'.repeat(32), recorded_at:1};}};}};
  await assert.rejects(recordDeletion(inconsistent, hash, 1));
});

test('Miniflare D1 journal accepts writes and prevents replacement or removal', async () => {
  const {Miniflare} = await import('miniflare');
  const mf = new Miniflare({modules:true, script:'export default {fetch(){return new Response("test")}}',
    compatibilityDate:'2025-11-01', d1Databases:['JOURNAL'], outboundService:{network:{allow:[]}}});
  try {
    const db = await mf.getD1Database('JOURNAL');
    const source = readFileSync(new URL('../journal-schema.sql', import.meta.url), 'utf8');
    let sql = '', trigger = false;
    for (const line of source.split('\n')) {
      if (!line.trim() || /^\s*--/.test(line)) continue;
      sql += line + '\n';
      if (/^\s*CREATE TRIGGER/i.test(line)) trigger = true;
      if ((!trigger && /;\s*$/.test(line)) || (trigger && /END;\s*$/.test(line))) {
        await db.prepare(sql).run(); sql = ''; trigger = false;
      }
    }
    assert.equal(sql.trim(), '');
    assert.equal(await recordDeletion(db, hash, 100), 100);
    assert.equal(await recordDeletion(db, hash, 500), 100);
    await db.prepare('INSERT OR REPLACE INTO deletion_journal VALUES (?, ?)').bind(hash, 999).run();
    assert.equal((await db.prepare('SELECT recorded_at FROM deletion_journal').first()).recorded_at, 100);
    await assert.rejects(db.prepare('DELETE FROM deletion_journal').run());
    await assert.rejects(db.prepare('UPDATE deletion_journal SET recorded_at = 999').run());
  } finally {await mf.dispose();}
});
