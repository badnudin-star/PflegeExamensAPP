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
function runtime(catalogStatus = 'ok', rateLimit = 1000) {
  const rates = Object.fromEntries(['REGISTRATION_RATE','READ_RATE','AUTH_RATE','ACCOUNT_RATE'].map((name, i) =>
    [name, {namespace_id:String(81001+i), simple:{limit:rateLimit, period:60}}]));
  return new Miniflare({workers:[
    {name:'ranking', modules:true, modulesRules:[{type:'ESModule',include:['**/*.js']}], scriptPath:fileURLToPath(new URL('fixtures/miniflare-harness.mjs',root)),
      compatibilityDate:'2025-11-01', cf:false, d1Databases:['DB'], ratelimits:rates,
      bindings:{QUIZ_CATALOG_URL:'https://catalog.test/content'}, outboundService:'catalog'},
    {name:'catalog', modules:true, scriptPath:fileURLToPath(new URL('fixtures/miniflare-catalog.mjs',root)),
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

test('Miniflare rate-limit binding throttles repeated reads', {timeout:30000}, async () => {
  const mf=runtime('ok',2);
  try {
    await sql(mf, statements(file('schema.sql')));
    assert.equal((await request(mf,'GET','/leaderboard')).status,200);
    assert.equal((await request(mf,'GET','/leaderboard')).status,200);
    assert.equal((await request(mf,'GET','/leaderboard')).status,429);
  } finally {await mf.dispose();}
});
