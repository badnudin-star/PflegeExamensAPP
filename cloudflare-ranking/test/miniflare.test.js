import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {Miniflare} from 'miniflare';

const root = new URL('../', import.meta.url);
const file = name => readFileSync(new URL(name, root), 'utf8');
// SQL files use one statement ending per line; trigger bodies end with END;.
function statements(source) {
  let current = '', trigger = false;
  const result = [];
  for (const line of source.split('\n')) {
    if (/^\s*--/.test(line) || !line.trim()) continue;
    current += `${line}\n`;
    if (/^\s*CREATE TRIGGER/i.test(line)) trigger = true;
    if ((!trigger && /;\s*$/.test(line)) || (trigger && /END;\s*$/.test(line))) {
      result.push({sql:current.trim()}); current = ''; trigger = false;
    }
  }
  assert.equal(current.trim(), '', 'unparsed SQL');
  return result;
}
function runtime(catalogStatus = 'ok', rateLimit = 1000, gateway = false, service = false) {
  const rates = Object.fromEntries(['REGISTRATION_RATE','READ_RATE','AUTH_RATE','ACCOUNT_RATE'].map((name, i) =>
    [name, {namespace_id:String(81001+i), simple:{limit:rateLimit, period:60}}]));
  const catalogUrl = gateway ? 'https://pflegeexamen-ranking-katalog-test.beispiel.workers.dev/content' : 'https://catalog.test/content';
  return new Miniflare({workers:[
    {name:'ranking', modules:true, modulesRules:[{type:'ESModule',include:['**/*.js','**/*.mjs']}], scriptPath:fileURLToPath(new URL('fixtures/miniflare-harness.mjs',root)),
      compatibilityDate:'2025-11-01', cf:false, d1Databases:['DB'], ratelimits:rates,
      bindings:{QUIZ_CATALOG_URL:catalogUrl, ...(gateway ? {TEST_GATEWAY:'1', TEST_ONLY_KEY:'f0'.repeat(32)} : {}),
        ...(service ? {QUIZ_CATALOG_TRANSPORT:'service'} : {})},
      ...(service ? {serviceBindings:{QUIZ_CATALOG_SERVICE:'catalog'}, outboundService:{network:{allow:[]}}} : {outboundService:'catalog'})},
    {name:'catalog', modules:true, scriptPath:fileURLToPath(new URL(gateway ? 'fixtures/synthetic-test-catalog.mjs' : 'fixtures/miniflare-catalog.mjs',root)),
      compatibilityDate:'2025-11-01', cf:false, bindings:{CATALOG_STATUS:catalogStatus}, outboundService:{network:{allow:[]}}}
  ]});
}
async function sql(mf, queries) {
  const r=await mf.dispatchFetch('https://ranking.test/__test/sql', {method:'POST',body:JSON.stringify({statements:queries})});
  const data=await r.json();
  assert.equal(r.status, 200, JSON.stringify(data));
  return data;
}
const token = 'ac'.repeat(32), second = 'bd'.repeat(32);
async function request(mf, method, path, body, auth=token, extra={}) {
  return mf.dispatchFetch('https://ranking.test'+path, {method,
    headers:{'Content-Type':'application/json','CF-Connecting-IP':'192.0.2.1', Authorization:'Bearer '+auth, ...extra},
    ...(body === undefined ? {} : {body:JSON.stringify(body)})});
}

test('Miniflare D1: registration, score, retry, closure, deletion and fail-closed behavior', {timeout:30000}, async () => {
  const mf = runtime();
  try {
    await sql(mf, statements(file('schema.sql')));
    assert.equal((await request(mf,'POST','/participants',{username:'Testperson'})).status,201);
    assert.equal((await request(mf,'POST','/participants',{username:'Testperson'})).status,200);
    assert.equal((await request(mf,'POST','/participants',{username:'Anders'})).status,409);
    assert.equal((await request(mf,'POST','/participants',{username:'Andere Person'},second)).status,201);
    const quizResponse = await request(mf,'GET','/quiz/today');
    assert.equal(quizResponse.status,200);
    const quiz=await quizResponse.json();
    assert.equal(quiz.questions.length,50);
    const body={date:quiz.date,fingerprint:quiz.fingerprint,answers:quiz.questions.map((q,i)=>({id:q.id,option:i===0?1:i%4}))};
    const scored=await request(mf,'POST','/quiz/attempt',body);
    assert.equal(scored.status,201);
    assert.deepEqual(await scored.json(),{date:quiz.date,correct:49,total:50});
    assert.equal((await request(mf,'POST','/quiz/attempt',body)).status,409);
    const result=await (await request(mf,'GET',`/quiz/attempt/result?date=${quiz.date}`)).json();
    assert.equal(result.payloadHash,createHash('sha256').update(JSON.stringify(body)).digest('hex'));
    assert.equal((await (await request(mf,'POST','/quiz/attempt/close',{date:quiz.date})).json()).status,'recorded');
    assert.deepEqual(await (await request(mf,'POST','/quiz/attempt/close',{date:quiz.date},second)).json(),{date:quiz.date,status:'closed'});
    assert.equal((await request(mf,'POST','/quiz/attempt',body,second)).status,409);
    assert.equal((await request(mf,'GET',`/quiz/attempt/result?date=${quiz.date}`,undefined,second)).status,410);
    const board=await (await request(mf,'GET','/leaderboard')).json();
    assert.deepEqual(board.entries.map(x=>[x.username,x.points]),[['Testperson',49]]);
    const deletion=await request(mf,'DELETE','/participants/me');
    assert.equal(deletion.status,204); assert.equal(deletion.headers.get('X-Quiz-Deletion-Receipt'),'v1');
    assert.equal((await request(mf,'DELETE','/participants/me')).headers.get('X-Quiz-Deletion-Receipt'),'v1');
    assert.equal((await request(mf,'POST','/participants',{username:'Testperson'})).status,410);
    assert.deepEqual((await (await request(mf,'GET','/leaderboard')).json()).entries,[]);
    const rows=await sql(mf,[{sql:'SELECT (SELECT count(*) FROM quiz_attempts) AS attempts, (SELECT count(*) FROM quiz_deleted_tokens) AS deleted, (SELECT generation FROM quiz_snapshot_generation WHERE id=1) AS generation'}]);
    assert.deepEqual(rows[0].results[0],{attempts:0,deleted:1,generation:1});
    assert.equal((await request(mf,'GET','/quiz/today',undefined,token,{'X-Test-Rate':'deny'})).status,429);
    assert.equal((await request(mf,'GET','/quiz/today',undefined,token,{'X-Test-Rate':'error'})).status,503);
    assert.equal((await request(mf,'GET','/quiz/today',undefined,token,{'X-Test-Bad-Catalog':'1'})).status,503);
    assert.equal((await request(mf,'GET','/leaderboard',undefined,token,{'X-Test-No-Db':'1'})).status,503);
    // A D1 batch rolls back all changes if a later statement fails.
    const failedBatch=await mf.dispatchFetch('https://ranking.test/__test/sql',{method:'POST',body:JSON.stringify({statements:[
      {sql:"INSERT INTO quiz_participants VALUES ('rollback', 'rollbackhash', 'Rollback', 1)"},
      {sql:"INSERT INTO quiz_participants VALUES ('duplicate', 'rollbackhash', 'Duplicate', 1)"}
    ]})});
    assert.equal(failedBatch.status,500);
    const rollback=await sql(mf,[{sql:"SELECT count(*) AS n FROM quiz_participants WHERE id='rollback'"}]);
    assert.equal(rollback[0].results[0].n,0);
  } finally { await mf.dispose(); }
});

test('Miniflare D1: migrations upgrade an existing prototype and enforce deletion trigger', {timeout:30000}, async () => {
  const mf=runtime();
  try {
    // Minimal pre-migration prototype including its existing snapshot generation table.
    await sql(mf, statements(`CREATE TABLE quiz_participants (id TEXT PRIMARY KEY, token_hash TEXT NOT NULL UNIQUE, username TEXT NOT NULL, created_at INTEGER NOT NULL);
CREATE TABLE quiz_attempts (participant_id TEXT NOT NULL REFERENCES quiz_participants(id), quiz_date TEXT NOT NULL, correct INTEGER NOT NULL, total INTEGER NOT NULL, submitted_at INTEGER NOT NULL, PRIMARY KEY(participant_id, quiz_date));
CREATE TABLE quiz_hourly_snapshot (id INTEGER PRIMARY KEY, payload_json TEXT NOT NULL, generated_at INTEGER NOT NULL);
CREATE TABLE quiz_snapshot_generation (id INTEGER PRIMARY KEY, generation INTEGER NOT NULL);
INSERT INTO quiz_snapshot_generation VALUES (1,0);
INSERT INTO quiz_participants VALUES ('legacy','legacyhash','Legacy',1);
INSERT INTO quiz_attempts VALUES ('legacy','2020-01-01',5,50,1);`));
    for (const migration of ['0001_attempt_payload_hash.sql','0002_attempt_closures.sql','0003_deletion_barrier.sql'])
      await sql(mf,statements(file('migrations/'+migration)));
    const rows=await sql(mf,[{sql:'SELECT payload_hash FROM quiz_attempts WHERE participant_id = ?',args:['legacy']}]);
    assert.equal(rows[0].results[0].payload_hash,null);
    await sql(mf,[{sql:'DELETE FROM quiz_attempts WHERE participant_id = ?',args:['legacy']}, {sql:'DELETE FROM quiz_participants WHERE id = ?',args:['legacy']}]);
    const barrier=await sql(mf,[{sql:'SELECT token_hash FROM quiz_deleted_tokens'}]);
    assert.equal(barrier[0].results[0].token_hash,'legacyhash');
    const failed=await mf.dispatchFetch('https://ranking.test/__test/sql',{method:'POST',body:JSON.stringify({statements:[{sql:"INSERT INTO quiz_participants VALUES ('new', 'legacyhash', 'Legacy', 2)"}]})});
    assert.equal(failed.status,500);
  } finally {await mf.dispose();}
});

for (const mode of ['redirect', 'error']) {
  test(`Miniflare: catalog ${mode} is rejected without following redirects`, {timeout:30000}, async () => {
    const mf=runtime(mode);
    try {
      await sql(mf, statements(file('schema.sql')));
      assert.equal((await request(mf,'GET','/quiz/today')).status,503);
    } finally {await mf.dispose();}
  });
}

test('Miniflare service binding: synthetic quiz and scoring without public network', {timeout:30000}, async () => {
  const mf=runtime('ok',1000,false,true);
  try {
    await sql(mf, statements(file('schema.sql')));
    assert.equal((await request(mf,'POST','/participants',{username:'Service Test'})).status,201);
    const response=await request(mf,'GET','/quiz/today');
    assert.equal(response.status,200);
    const quiz=await response.json();
    assert.equal(quiz.questions.length,50);
    assert.ok(quiz.questions.every((q,i)=>q.id===`synthetic-ranking-${i}`));
    const body={date:quiz.date,fingerprint:quiz.fingerprint,answers:quiz.questions.map(q=>({id:q.id,option:q.correctIndex}))};
    const result=await request(mf,'POST','/quiz/attempt',body);
    assert.equal(result.status,201);
    assert.equal((await result.json()).correct,50);
  } finally {await mf.dispose();}
});

for (const mode of ['redirect', 'error']) {
  test(`Miniflare service binding: catalog ${mode} is rejected without fallback`, {timeout:30000}, async () => {
    const mf=runtime(mode,1000,false,true);
    try {
      await sql(mf, statements(file('schema.sql')));
      assert.equal((await request(mf,'GET','/quiz/today')).status,503);
    } finally {await mf.dispose();}
  });
}

test('Miniflare D1: concurrent submissions share one daily slot', {timeout:30000}, async () => {
  const mf=runtime();
  try {
    await sql(mf, statements(file('schema.sql')));
    assert.equal((await request(mf,'POST','/participants',{username:'Parallel'})).status,201);
    const quiz=await (await request(mf,'GET','/quiz/today')).json();
    const body={date:quiz.date, fingerprint:quiz.fingerprint, answers:quiz.questions.map((q,i)=>({id:q.id,option:i%4}))};
    const responses=await Promise.all(Array.from({length:5},()=>request(mf,'POST','/quiz/attempt',body)));
    assert.deepEqual(responses.map(r=>r.status).sort(),[201,409,409,409,409]);
    const rows=await sql(mf,[{sql:'SELECT count(*) AS n FROM quiz_attempts'}]);
    assert.equal(rows[0].results[0].n,1);
  } finally {await mf.dispose();}
});

test('Miniflare D1: result access and deletion are isolated between accounts', {timeout:30000}, async () => {
  const mf=runtime();
  try {
    await sql(mf, statements(file('schema.sql')));
    assert.equal((await request(mf,'POST','/participants',{username:'Test A'},token)).status,201);
    assert.equal((await request(mf,'POST','/participants',{username:'Test B'},second)).status,201);
    const quiz=await (await request(mf,'GET','/quiz/today')).json();
    const body={date:quiz.date, fingerprint:quiz.fingerprint, answers:quiz.questions.map((q,i)=>({id:q.id,option:i%4}))};
    assert.equal((await request(mf,'POST','/quiz/attempt',body,token)).status,201);
    assert.equal((await request(mf,'GET',`/quiz/attempt/result?date=${quiz.date}`,undefined,second)).status,404);
    assert.equal((await request(mf,'GET',`/quiz/attempt/result?date=${quiz.date}`,undefined,'ef'.repeat(32))).status,401);
    const own=await (await request(mf,'GET',`/quiz/attempt/result?date=${quiz.date}`,undefined,token)).json();
    assert.equal(own.correct,50);
    assert.equal(own.payloadHash,createHash('sha256').update(JSON.stringify(body)).digest('hex'));
    const board=await (await request(mf,'GET','/leaderboard')).json();
    assert.deepEqual(board.entries.map(entry=>entry.username),['Test A']);
    for (const secret of [token,second,own.payloadHash]) assert.ok(!JSON.stringify(board).includes(secret));
    assert.equal((await request(mf,'DELETE','/participants/me',undefined,second)).status,204);
    const stillOwn=await request(mf,'GET',`/quiz/attempt/result?date=${quiz.date}`,undefined,token);
    assert.equal(stillOwn.status,200);
    assert.deepEqual(await stillOwn.json(),own);
    const boardAfter=await (await request(mf,'GET','/leaderboard')).json();
    assert.deepEqual(boardAfter.entries.map(entry=>entry.username),['Test A']);
    assert.equal((await request(mf,'POST','/participants',{username:'Test B'},second)).status,410);
  } finally {await mf.dispose();}
});

test('Miniflare D1: submission racing closure leaves exactly one durable outcome', {timeout:30000}, async () => {
  const mf=runtime();
  try {
    await sql(mf, statements(file('schema.sql')));
    const quiz=await (await request(mf,'GET','/quiz/today')).json();
    const body={date:quiz.date, fingerprint:quiz.fingerprint, answers:quiz.questions.map((q,i)=>({id:q.id,option:i%4}))};
    for (let i=0; i<16; i++) {
      const auth=(i+1).toString(16).padStart(64,'0');
      assert.equal((await request(mf,'POST','/participants',{username:`Race ${i}`},auth)).status,201);
      const [submission, closure]=await Promise.all([
        request(mf,'POST','/quiz/attempt',body,auth),
        request(mf,'POST','/quiz/attempt/close',{date:quiz.date},auth),
      ]);
      assert.equal(closure.status,200);
      const closed=await closure.json();
      const result=await request(mf,'GET',`/quiz/attempt/result?date=${quiz.date}`,undefined,auth);
      if (closed.status === 'closed') {
        assert.equal(submission.status,409);
        assert.equal(result.status,410);
        assert.deepEqual(await result.json(), {date:quiz.date,status:'closed'});
      } else {
        assert.equal(closed.status,'recorded');
        assert.equal(submission.status,201);
        assert.equal(result.status,200);
        const saved=await result.json();
        assert.equal(saved.correct,50);
        assert.equal(saved.payloadHash,createHash('sha256').update(JSON.stringify(body)).digest('hex'));
        assert.equal(closed.payloadHash,saved.payloadHash);
      }
      const state=await sql(mf,[{sql:`SELECT
        (SELECT count(*) FROM quiz_attempts a JOIN quiz_participants p ON p.id=a.participant_id WHERE p.token_hash=?) AS attempts,
        (SELECT count(*) FROM quiz_attempt_closures c JOIN quiz_participants p ON p.id=c.participant_id WHERE p.token_hash=?) AS closures`,
        args:[createHash('sha256').update(auth).digest('hex'),createHash('sha256').update(auth).digest('hex')]}]);
      assert.equal(state[0].results[0].attempts + state[0].results[0].closures,1);
    }
  } finally {await mf.dispose();}
});

test('Miniflare D1: concurrent account deletion and submission never resurrect an account', {timeout:30000}, async () => {
  const mf=runtime();
  try {
    await sql(mf, statements(file('schema.sql')));
    const quiz=await (await request(mf,'GET','/quiz/today')).json();
    const body={date:quiz.date, fingerprint:quiz.fingerprint, answers:quiz.questions.map((q,i)=>({id:q.id,option:i%4}))};
    for (let i=0; i<16; i++) {
      const auth=(i+100).toString(16).padStart(64,'0'), name=`Delete ${i}`;
      assert.equal((await request(mf,'POST','/participants',{username:name},auth)).status,201);
      const [submission, deletion]=await Promise.all([
        request(mf,'POST','/quiz/attempt',body,auth),
        request(mf,'DELETE','/participants/me',undefined,auth),
      ]);
      assert.ok([201,401,503].includes(submission.status), `unexpected submission status ${submission.status}`);
      assert.equal(deletion.status,204);
      assert.equal(deletion.headers.get('X-Quiz-Deletion-Receipt'),'v1');
      assert.equal((await request(mf,'POST','/participants',{username:name},auth)).status,410);
      assert.equal((await request(mf,'GET',`/quiz/attempt/result?date=${quiz.date}`,undefined,auth)).status,401);
      const hash=createHash('sha256').update(auth).digest('hex');
      const state=await sql(mf,[{sql:`SELECT
        (SELECT count(*) FROM quiz_participants WHERE token_hash=?) AS participants,
        (SELECT count(*) FROM quiz_deleted_tokens WHERE token_hash=?) AS barriers,
        (SELECT count(*) FROM quiz_attempts a LEFT JOIN quiz_participants p ON p.id=a.participant_id WHERE p.id IS NULL) AS orphan_attempts`,
        args:[hash,hash]}]);
      assert.deepEqual(state[0].results[0],{participants:0,barriers:1,orphan_attempts:0});
      const board=await (await request(mf,'GET','/leaderboard')).json();
      assert.ok(!board.entries.some(entry=>entry.username === name));
    }
  } finally {await mf.dispose();}
});

for (const service of [false, true]) test(`Miniflare: guarded synthetic gateway exercises the ${service ? 'service binding' : 'HTTP'} worker`, {timeout:30000}, async () => {
  const mf=runtime('ok',1000,true,service);
  const base='https://pflegeexamen-ranking-test.beispiel.workers.dev';
  const key='f0'.repeat(32);
  const call=(method,path,body,testKey=key) => mf.dispatchFetch(base+path, {method,
    headers:{'X-Pflege-Test-Key':testKey,'Content-Type':'application/json','CF-Connecting-IP':'192.0.2.1',Authorization:'Bearer '+token},
    ...(body === undefined ? {} : {body:JSON.stringify(body)})});
  try {
    // This SQL harness exists only in local tests and must never be deployed.
    await sql(mf, statements(file('schema.sql')));
    assert.equal((await call('GET','/health',undefined,'00'.repeat(32))).status,404);
    assert.equal((await call('GET','/health')).status,200);
    assert.equal((await call('POST','/participants',{username:'Nur Test'})).status,201);
    const quiz=await (await call('GET','/quiz/today')).json();
    assert.equal(quiz.questions.length,50);
    assert.ok(quiz.questions.every(q=>q.id.startsWith('synthetic-ranking-')));
    const body={date:quiz.date,fingerprint:quiz.fingerprint,answers:quiz.questions.map(q=>({id:q.id,option:q.correctIndex}))};
    assert.equal((await call('POST','/quiz/attempt',body)).status,201);
    const result=await (await call('GET',`/quiz/attempt/result?date=${quiz.date}`)).json();
    assert.equal(result.correct,50);
    const deleted=await call('DELETE','/participants/me');
    assert.equal(deleted.status,204);
    assert.equal(deleted.headers.get('X-Quiz-Deletion-Receipt'),'v1');
    assert.equal((await call('POST','/participants',{username:'Nur Test'})).status,410);
  } finally {await mf.dispose();}
});

test('Miniflare rate-limit binding throttles repeated reads', {timeout:30000}, async () => {
  const mf=runtime('ok',2);
  try {
    await sql(mf, statements(file('schema.sql')));
    assert.equal((await request(mf,'GET','/leaderboard')).status,200);
    assert.equal((await request(mf,'GET','/leaderboard')).status,200);
    assert.equal((await request(mf,'GET','/leaderboard')).status,429);
  } finally {await mf.dispose();}
});
