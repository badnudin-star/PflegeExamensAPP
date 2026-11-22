import { test } from 'node:test';
import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import worker from '../src/worker.js';

globalThis.crypto ??= webcrypto;
const token = 'x'.repeat(32);
const quiz = (id, date) => ({id, published:date, topic:'Thema', question:'Frage', options:['A','B','C','D'], correctIndex:0, explanation:'Erklärung', sources:['Test']});
const catalog = (revision, quizzes) => ({schemaVersion:1, revision, notes:[], cards:[], cases:[], quizzes});
function db() {
  const sqlite = new DatabaseSync(':memory:');
  sqlite.exec(readFileSync(new URL('../schema.sql', import.meta.url), 'utf8'));
  return {
    sqlite,
    prepare(sql) {
      let args = [];
      return {
        bind(...values) { args = values; return this; },
        async first() { return sqlite.prepare(sql).get(...args) ?? null; },
        async all() { return {results: sqlite.prepare(sql).all(...args)}; },
        async run() { return {meta:{changes:Number(sqlite.prepare(sql).run(...args).changes)}}; }
      };
    }
  };
}
const req = (method, path, body, revision = 0, auth = token) => new Request('https://example.test'+path, {
  method, headers:{Authorization:'Bearer '+auth, 'If-Match':String(revision)},
  ...(body === undefined ? {} : {body:JSON.stringify(body)})
});
const env = () => ({DB:db(), PUBLISH_TOKEN:token});
const send = (e, method, path, body, revision = 0, auth = token) => worker.fetch(req(method,path,body,revision,auth), e);

test('archive exact copies and reject unsafe removals, changes, or conflicting writers', async () => {
  const e = env();
  const questions = [quiz('one','2020-01-01'), quiz('two','2020-01-01'), quiz('three','2020-01-02')];
  assert.equal((await send(e,'PUT','/admin/content',catalog(1,questions))).status, 200);
  assert.equal((await send(e,'POST','/admin/quiz-archive/2020-01-01',undefined,1,'wrong')).status, 401);
  assert.equal((await send(e,'POST','/admin/quiz-archive/2020-01-01',undefined,2)).status, 409);
  assert.equal((await send(e,'POST','/admin/quiz-archive/2020-01-03',undefined,1)).status, 404);
  assert.equal((await send(e,'PUT','/admin/content',catalog(2,[questions[2]]),1)).status, 409);
  assert.equal((await send(e,'POST','/admin/quiz-archive/2020-01-01',undefined,1)).status, 201);
  assert.equal((await send(e,'POST','/admin/quiz-archive/2020-01-01',undefined,1)).status, 200);
  assert.deepEqual(await (await send(e,'GET','/quiz-archive/2020-01-01')).json(), {date:'2020-01-01', quizzes:questions.slice(0,2)});
  assert.equal((await send(e,'PUT','/admin/content',catalog(2,questions.slice(1)),1)).status, 409); // partial day
  assert.equal((await send(e,'PUT','/admin/content',catalog(2,[{...questions[0],published:'2020-01-05'},questions[2]]),1)).status, 409); // moved id
  assert.equal((await send(e,'PUT','/admin/content',catalog(2,[questions[2]]),1)).status, 200);
  assert.equal((await send(e,'PUT','/admin/content',catalog(3,[]),1)).status, 409); // stale revision
  assert.deepEqual((await (await send(e,'GET','/content')).json()).quizzes, [questions[2]]);
  assert.deepEqual((await (await send(e,'GET','/quiz-archive/2020-01-01')).json()).quizzes, questions.slice(0,2));

  // A later corrected day cannot silently overwrite the immutable copy.
  const changed = [{...questions[0], question:'Anders'}, questions[1], questions[2]];
  assert.equal((await send(e,'PUT','/admin/content',catalog(3,changed),2)).status, 200);
  assert.equal((await send(e,'POST','/admin/quiz-archive/2020-01-01',undefined,3)).status, 409);
  assert.equal((await send(e,'PUT','/admin/content',catalog(4,[questions[2]]),3)).status, 409);
});

test('archive copies are bounded, paginated, read-only and never remove current day', async () => {
  const e = env();
  const today = new Intl.DateTimeFormat('en-CA', {timeZone:'Europe/Berlin',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());
  const questions = [quiz('a','2020-01-01'), quiz('b','2020-01-02'), quiz('c','2020-01-03'), quiz('today',today)];
  assert.equal((await send(e,'PUT','/admin/content',catalog(1,questions))).status, 200);
  for (const date of ['2020-01-01','2020-01-02','2020-01-03',today])
    assert.equal((await send(e,'POST',`/admin/quiz-archive/${date}`,undefined,1)).status, 201);
  assert.equal((await send(e,'PUT','/admin/content',catalog(2,questions.slice(0,3)),1)).status, 409);
  const p1 = await (await send(e,'GET','/quiz-archive?limit=2')).json();
  assert.deepEqual(p1.days.map(x=>x.date), [today,'2020-01-03']);
  assert.equal(p1.nextBefore,'2020-01-03');
  const p2 = await (await send(e,'GET','/quiz-archive?limit=2&before=2020-01-03')).json();
  assert.deepEqual(p2.days.map(x=>x.date), ['2020-01-02','2020-01-01']);
  assert.equal(p2.nextBefore,null);
  for (const path of ['/quiz-archive?limit=31','/quiz-archive?limit=0','/quiz-archive?before=2020-02-30','/quiz-archive?limit=1&limit=2','/quiz-archive?foo=bar','/quiz-archive/2020-02-30'])
    assert.equal((await send(e,'GET',path)).status, 400);
  assert.equal((await send(e,'GET','/quiz-archive/2019-01-01')).status, 404);
  assert.equal((await send(e,'DELETE','/quiz-archive/2020-01-01')).status, 404);
});

test('old database without migration keeps normal publishing working but blocks archival', async () => {
  const e = env();
  e.DB.sqlite.exec('DROP TABLE quiz_archive');
  const q = quiz('q','2020-01-01');
  assert.equal((await send(e,'PUT','/admin/content',catalog(1,[q]))).status, 200);
  assert.equal((await send(e,'PUT','/admin/content',catalog(2,[q]),1)).status, 200);
  assert.equal((await send(e,'POST','/admin/quiz-archive/2020-01-01',undefined,2)).status, 503);
  assert.equal((await send(e,'PUT','/admin/content',catalog(3,[]),2)).status, 503);
  assert.equal((await send(e,'GET','/quiz-archive')).status, 503);
});

test('revision change between archive read and insert prevents stale copy', async () => {
  const e = env();
  const first = catalog(1,[quiz('a','2020-01-01')]);
  const newer = catalog(2,[quiz('b','2020-01-01')]);
  assert.equal((await send(e,'PUT','/admin/content',first)).status, 200);
  const original = e.DB.prepare;
  e.DB.prepare = function(sql) {
    const statement = original.call(this, sql);
    if (sql.includes('INSERT INTO quiz_archive')) {
      const run = statement.run;
      statement.run = async () => {
        this.sqlite.prepare('UPDATE content_snapshot SET revision = ?, payload = ? WHERE id = 1').run(2,JSON.stringify(newer));
        return run.call(statement);
      };
    }
    return statement;
  };
  assert.equal((await send(e,'POST','/admin/quiz-archive/2020-01-01',undefined,1)).status, 409);
  assert.equal((await send(e,'GET','/quiz-archive/2020-01-01')).status, 404);
});
