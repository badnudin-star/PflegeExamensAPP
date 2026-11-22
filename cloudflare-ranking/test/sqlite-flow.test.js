import {test} from 'node:test';
import assert from 'node:assert/strict';
import {webcrypto, createHash} from 'node:crypto';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import worker from '../src/worker.js';

globalThis.crypto ??= webcrypto;
const day = () => new Intl.DateTimeFormat('en-CA',{timeZone:'Europe/Berlin',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());
const token = 'ac'.repeat(32);
function db() {
  const sqlite = new DatabaseSync(':memory:');
  sqlite.exec('PRAGMA foreign_keys = ON');
  sqlite.exec(readFileSync(new URL('../schema.sql', import.meta.url),'utf8'));
  return {
    sqlite, failBatch: false,
    prepare(sql) {
      let args = [];
      return {
        bind(...values) {args = values; return this;},
        async first() {return sqlite.prepare(sql).get(...args) ?? null;},
        async all() {return {results:sqlite.prepare(sql).all(...args)};},
        async run() {return {meta:{changes:Number(sqlite.prepare(sql).run(...args).changes)}};}
      };
    },
    async batch(statements) {
      sqlite.exec('BEGIN');
      try {
        for (let i=0; i<statements.length; i++) {
          if (this.failBatch && i === 1) throw new Error('simulated failure');
          await statements[i].run();
        }
        sqlite.exec('COMMIT');
      } catch (e) { sqlite.exec('ROLLBACK'); throw e; }
    }
  };
}
const req = (method, path, body, auth = token) => new Request('https://rank.test'+path, {
  method, headers:{'Content-Type':'application/json', 'CF-Connecting-IP':'192.0.2.1', Authorization:'Bearer '+auth},
  ...(body === undefined ? {} : {body:JSON.stringify(body)})
});
function environment() {
  const allowed = {async limit() {return {success:true};}};
  return {DB:db(), QUIZ_CATALOG_URL:'https://catalog.test/content', REGISTRATION_RATE:allowed, READ_RATE:allowed, AUTH_RATE:allowed, ACCOUNT_RATE:allowed};
}

test('real SQLite schema scores and deletes an account atomically; no old XP involved', async () => {
  const e = environment(), originalFetch = globalThis.fetch;
  const questions = Array.from({length:50}, (_,i) => ({id:`q-${i}`, published:day(), topic:'Test', question:`Frage ${i}`,
    options:['A','B','C','D'], correctIndex:i%4, explanation:'Test'}));
  globalThis.fetch = async () => new Response(JSON.stringify({schemaVersion:1, quizzes:questions}));
  try {
    assert.equal((await worker.fetch(req('POST','/participants',{username:'Testperson'}),e)).status,201);
    const quiz = await (await worker.fetch(req('GET','/quiz/today'),e)).json();
    const answers = questions.map((q,i) => ({id:q.id, option:i===0 ? 1 : i%4}));
    const body = {date:quiz.date, fingerprint:quiz.fingerprint, answers};
    const score = await worker.fetch(req('POST','/quiz/attempt',body),e);
    assert.equal(score.status,201);
    assert.deepEqual(await score.json(),{date:quiz.date, correct:49, total:50});
    assert.equal((await worker.fetch(req('POST','/quiz/attempt',body),e)).status,409);
    const result = await (await worker.fetch(req('GET',`/quiz/attempt/result?date=${quiz.date}`),e)).json();
    assert.equal(result.payloadHash,createHash('sha256').update(JSON.stringify(body)).digest('hex'));
    const board = await (await worker.fetch(req('GET','/leaderboard'),e)).json();
    assert.deepEqual(board.entries.map(x=>[x.username,x.points]),[['Testperson',49]]);
    assert.equal(e.DB.sqlite.prepare('SELECT COUNT(*) n FROM quiz_attempts').get().n,1);
    // Simulate D1 batch failure after deleting attempts; no half-deletion survives rollback.
    e.DB.failBatch = true;
    assert.equal((await worker.fetch(req('DELETE','/participants/me'),e)).status,503);
    assert.equal(e.DB.sqlite.prepare('SELECT COUNT(*) n FROM quiz_attempts').get().n,1);
    assert.equal(e.DB.sqlite.prepare('SELECT COUNT(*) n FROM quiz_participants').get().n,1);
    assert.equal(e.DB.sqlite.prepare('SELECT COUNT(*) n FROM quiz_hourly_snapshot').get().n,1);
    e.DB.failBatch = false;
    assert.equal((await worker.fetch(req('DELETE','/participants/me'),e)).status,204);
    assert.equal(e.DB.sqlite.prepare('SELECT COUNT(*) n FROM quiz_attempts').get().n,0);
    assert.equal(e.DB.sqlite.prepare('SELECT COUNT(*) n FROM quiz_participants').get().n,0);
    assert.equal(e.DB.sqlite.prepare('SELECT COUNT(*) n FROM quiz_hourly_snapshot').get().n,0);
    assert.equal(e.DB.sqlite.prepare('SELECT generation FROM quiz_snapshot_generation').get().generation,1);
    assert.deepEqual((await (await worker.fetch(req('GET','/leaderboard'),e)).json()).entries,[]);
  } finally { globalThis.fetch = originalFetch; }
});

test('closing an uncertain attempt blocks a delayed submission, remains idempotent, and is deleted with the account', async () => {
  const e = environment(), originalFetch = globalThis.fetch;
  const questions = Array.from({length:50}, (_,i) => ({id:`q-${i}`, published:day(), topic:'Test', question:`Frage ${i}`,
    options:['A','B','C','D'], correctIndex:i%4, explanation:'Test'}));
  let blockFetch = false, releaseFetch, signalFetch;
  const gate = new Promise(resolve => { releaseFetch = resolve; });
  const started = new Promise(resolve => { signalFetch = resolve; });
  globalThis.fetch = async () => {
    if (blockFetch) { signalFetch(); await gate; }
    return new Response(JSON.stringify({schemaVersion:1, quizzes:questions}));
  };
  try {
    assert.equal((await worker.fetch(req('POST','/participants',{username:'Testperson'}),e)).status,201);
    const second = 'bd'.repeat(32);
    assert.equal((await worker.fetch(req('POST','/participants',{username:'Andere Person'},second),e)).status,201);
    const quiz = await (await worker.fetch(req('GET','/quiz/today'),e)).json();
    const body = {date:quiz.date, fingerprint:quiz.fingerprint, answers:questions.map((q,i)=>({id:q.id, option:i%4}))};
    blockFetch = true;
    const delayed = worker.fetch(req('POST','/quiz/attempt',body),e);
    await started;
    const close = await worker.fetch(req('POST','/quiz/attempt/close',{date:quiz.date}),e);
    assert.equal(close.status,200);
    assert.deepEqual(await close.json(),{date:quiz.date,status:'closed'});
    releaseFetch();
    assert.equal((await delayed).status,409);
    blockFetch = false;
    assert.equal((await worker.fetch(req('POST','/quiz/attempt',body,second),e)).status,201);
    const closedStatus = await worker.fetch(req('GET',`/quiz/attempt/result?date=${quiz.date}`),e);
    assert.equal(closedStatus.status,410);
    assert.deepEqual(await closedStatus.json(),{date:quiz.date,status:'closed'});
    const otherStatus = await worker.fetch(req('GET',`/quiz/attempt/result?date=${quiz.date}`,undefined,second),e);
    assert.equal(otherStatus.status,200);
    assert.deepEqual(await (await worker.fetch(req('POST','/quiz/attempt/close',{date:quiz.date}),e)).json(),{date:quiz.date,status:'closed'});
    assert.equal(e.DB.sqlite.prepare('SELECT COUNT(*) n FROM quiz_attempt_closures').get().n,1);
    assert.equal((await worker.fetch(req('DELETE','/participants/me'),e)).status,204);
    assert.equal(e.DB.sqlite.prepare('SELECT COUNT(*) n FROM quiz_attempt_closures').get().n,0);
    assert.equal(e.DB.sqlite.prepare('SELECT COUNT(*) n FROM quiz_attempts').get().n,1);
    assert.equal(e.DB.sqlite.prepare('SELECT COUNT(*) n FROM quiz_participants').get().n,1);
  } finally { releaseFetch(); globalThis.fetch = originalFetch; }
});

test('closure returns an existing score and never closes a recorded attempt', async () => {
  const e = environment(), originalFetch = globalThis.fetch;
  const questions = Array.from({length:50}, (_,i) => ({id:`q-${i}`, published:day(), topic:'Test', question:`Frage ${i}`,
    options:['A','B','C','D'], correctIndex:i%4, explanation:'Test'}));
  globalThis.fetch = async () => new Response(JSON.stringify({schemaVersion:1,quizzes:questions}));
  try {
    await worker.fetch(req('POST','/participants',{username:'Testperson'}),e);
    const quiz = await (await worker.fetch(req('GET','/quiz/today'),e)).json();
    const body = {date:quiz.date,fingerprint:quiz.fingerprint,answers:questions.map((q,i)=>({id:q.id,option:i%4}))};
    assert.equal((await worker.fetch(req('POST','/quiz/attempt',body),e)).status,201);
    const result = await (await worker.fetch(req('POST','/quiz/attempt/close',{date:quiz.date}),e)).json();
    assert.deepEqual(result,{date:quiz.date,status:'recorded',correct:50,total:50,payloadHash:createHash('sha256').update(JSON.stringify(body)).digest('hex')});
    assert.equal(e.DB.sqlite.prepare('SELECT COUNT(*) n FROM quiz_attempt_closures').get().n,0);
    for (const invalid of [{date:'2100-01-01'}, {date:'2020-02-30'}, {date:quiz.date, extra:true}, {}])
      assert.equal((await worker.fetch(req('POST','/quiz/attempt/close',invalid),e)).status,400);
    assert.equal((await worker.fetch(req('POST','/quiz/attempt/close',{date:quiz.date},'f'.repeat(64)),e)).status,401);
  } finally { globalThis.fetch = originalFetch; }
});

test('deletion survives a lost response and prevents a delayed or legacy registration from resurrecting the account', async () => {
  const e=environment();
  assert.equal((await worker.fetch(req('DELETE','/participants/me',undefined,'00'.repeat(32)),e)).status,401);
  assert.equal(e.DB.sqlite.prepare('SELECT count(*) n FROM quiz_deleted_tokens').get().n,0);
  assert.equal((await worker.fetch(req('POST','/participants',{username:'Testperson'}),e)).status,201);
  let start, release;
  const started = new Promise(resolve => { start=resolve; });
  const gate = new Promise(resolve => { release=resolve; });
  const stream = new ReadableStream({async pull(controller) {
    start(); await gate; controller.enqueue(new TextEncoder().encode('{"username":"Testperson"}')); controller.close();
  }});
  const delayed = worker.fetch(new Request('https://rank.test/participants',{method:'POST',duplex:'half',
    headers:{'Content-Type':'application/json','CF-Connecting-IP':'192.0.2.1',Authorization:'Bearer '+token},body:stream}),e);
  try {
    await started;
    const first = await worker.fetch(req('DELETE','/participants/me'),e);
    assert.equal(first.status,204); assert.equal(first.headers.get('X-Quiz-Deletion-Receipt'),'v1');
    // Treat the first 204 as lost; replaying deletion returns a durable receipt.
    const replay = await worker.fetch(req('DELETE','/participants/me'),e);
    assert.equal(replay.status,204); assert.equal(replay.headers.get('X-Quiz-Deletion-Receipt'),'v1');
    release();
    assert.equal((await delayed).status,410);
    const hash=createHash('sha256').update(token).digest('hex');
    assert.equal(e.DB.sqlite.prepare('SELECT token_hash FROM quiz_deleted_tokens').get().token_hash,hash);
    assert.equal(e.DB.sqlite.prepare('SELECT count(*) n FROM quiz_participants').get().n,0);
    assert.throws(()=>e.DB.sqlite.prepare('INSERT INTO quiz_participants VALUES(?,?,?,?)').run('old-worker',hash,'Old',1));
    assert.equal((await worker.fetch(req('POST','/participants',{username:'Testperson'}),e)).status,410);
  } finally { release(); }
});
