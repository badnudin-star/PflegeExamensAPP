import {test} from 'node:test';
import assert from 'node:assert/strict';
import {webcrypto} from 'node:crypto';
import worker, {scoreAnswers} from '../src/worker.js';
globalThis.crypto ??= webcrypto;
const day = () => new Intl.DateTimeFormat('en-CA', {timeZone:'Europe/Berlin', year:'numeric', month:'2-digit', day:'2-digit'}).format(new Date());
const questions = () => Array.from({length:50}, (_, i) => ({id:`q-${i}`, published:day(), topic:'Pflege', question:`Frage ${i}?`, options:['a','b','c','d'], correctIndex:i % 4, explanation:`Erklärung ${i}`}));
function database() {
  const participants = [], attempts = [], state = {participants, attempts, snapshot:null, generation:0, onSnapshotWrite:null};
  return {state, async batch(statements) { for (const s of statements) await s.run(); }, prepare(sql) {
    let args=[];
    return {bind(...values) {args=values; return this;}, async run() {
      if (sql.includes('INSERT INTO quiz_participants')) { participants.push({id:args[0], hash:args[1], username:args[2]}); return {meta:{changes:1}}; }
      if (sql.includes('INSERT INTO quiz_attempts')) {
        if (attempts.some(a => a.id === args[0] && a.date === args[1])) return {meta:{changes:0}};
        attempts.push({id:args[0], date:args[1], points:args[2], total:args[3]}); return {meta:{changes:1}};
      }
      if (sql.includes('INSERT INTO quiz_hourly_snapshot')) {
        if (state.onSnapshotWrite) { const hook=state.onSnapshotWrite; state.onSnapshotWrite=null; await hook(); }
        if (args[2] !== state.generation) return {meta:{changes:0}};
        state.snapshot=args[0]; return {meta:{changes:1}};
      }
      if (sql.includes('DELETE FROM quiz_attempts')) { for (let i=attempts.length-1; i>=0; i--) if (attempts[i].id===args[0]) attempts.splice(i,1); return {meta:{changes:1}}; }
      if (sql.includes('DELETE FROM quiz_participants')) { for (let i=participants.length-1; i>=0; i--) if (participants[i].id===args[0]) participants.splice(i,1); return {meta:{changes:1}}; }
      if (sql.includes('DELETE FROM quiz_hourly_snapshot')) { state.snapshot=null; return {meta:{changes:1}}; }
      if (sql.includes('UPDATE quiz_snapshot_generation')) { state.generation++; return {meta:{changes:1}}; }
      throw Error('unexpected run');
    }, async first() {
      if (sql.includes('FROM quiz_snapshot_generation')) return {generation:state.generation};
      if (sql.includes('FROM quiz_participants WHERE')) return participants.find(p => p.hash === args[0]) || null;
      if (sql.includes('COUNT(DISTINCT participant_id)')) return {count:new Set(attempts.map(a => a.id)).size};
      if (sql.includes('FROM quiz_hourly_snapshot')) return state.snapshot ? {payload_json:state.snapshot} : null;
      throw Error('unexpected first');
    }, async all() {
      if (!sql.includes('FROM quiz_attempts')) throw Error('unexpected all');
      return {results:participants.map(p => ({username:p.username, points:attempts.filter(a=>a.id===p.id).reduce((n,a)=>n+a.points,0), quizzes:attempts.filter(a=>a.id===p.id).length})).filter(p=>p.quizzes).sort((a,b)=>b.points-a.points)};
    }};
  }};
}
function request(method, path, data, token) {
  const headers = {'Content-Type':'application/json', 'CF-Connecting-IP':'192.0.2.1'};
  if (token) headers.Authorization = `Bearer ${token}`;
  return new Request(`https://rank.test${path}`, {method, headers, ...(data === undefined ? {} : {body:JSON.stringify(data)})});
}
function env() {
  const allowed = {async limit() {return {success:true};}};
  return {DB:database(), QUIZ_CATALOG_URL:'https://catalog.test/content',
    REGISTRATION_RATE:allowed, READ_RATE:allowed, AUTH_RATE:allowed, ACCOUNT_RATE:allowed};
}
const answerSet = questions => questions.map((q,i)=>({id:q.id, option:i%4}));

test('only exact, unique answers are scored by the server', () => {
  const q=questions(), a=answerSet(q);
  assert.equal(scoreAnswers(q,a),50);
  a[0].option=1; assert.equal(scoreAnswers(q,a),49);
  assert.throws(()=>scoreAnswers(q,a.slice(1)));
  assert.throws(()=>scoreAnswers(q,[...a.slice(0,49),a[0]]));
  assert.throws(()=>scoreAnswers(q,a.map(x=>({...x,option:4}))));
});

test('no legacy points accepted, grading and one attempt per account/day', async () => {
  const original=globalThis.fetch, quiz=questions(), e=env();
  globalThis.fetch=async () => new Response(JSON.stringify({schemaVersion:1,quizzes:quiz}));
  try {
    assert.equal((await worker.fetch(request('POST','/score',{totalXp:500000}),e)).status,404);
    assert.equal((await worker.fetch(request('POST','/participants',{username:'x',totalXp:500}),e)).status,400);
    const reg=await worker.fetch(request('POST','/participants',{username:'PflegeFuchs'}),e);
    assert.equal(reg.status,201); const {token}=await reg.json(); assert.match(token,/^[0-9a-f]{64}$/);
    assert.notEqual(e.DB.state.participants[0].hash,token);
    const get=await worker.fetch(request('GET','/quiz/today'),e);
    assert.equal(get.status,200); const visible=await get.json();
    assert.equal(visible.questions.length,50);
    assert.equal(visible.questions[0].correctIndex,quiz[0].correctIndex);
    assert.equal(visible.questions[0].explanation,quiz[0].explanation);
    const body={date:visible.date, fingerprint:visible.fingerprint, answers:answerSet(quiz)};
    assert.equal((await worker.fetch(request('POST','/quiz/attempt',body,'0'.repeat(64)),e)).status,401);
    assert.equal((await worker.fetch(request('POST','/quiz/attempt',{...body, points:500},token),e)).status,400);
    assert.equal((await worker.fetch(request('POST','/quiz/attempt',{...body,fingerprint:'old'},token),e)).status,409);
    const graded=await worker.fetch(request('POST','/quiz/attempt',body,token),e);
    assert.equal(graded.status,201); assert.deepEqual(await graded.json(),{date:day(),correct:50,total:50});
    assert.equal((await worker.fetch(request('POST','/quiz/attempt',body,token),e)).status,409);
    const board=await (await worker.fetch(request('GET','/leaderboard'),e)).json();
    assert.equal(board.entries[0].points,50); assert.equal(board.entries[0].username,'PflegeFuchs');
    assert.equal(board.entries[0].id,undefined); assert.equal(board.entries[0].token,undefined);
  } finally { globalThis.fetch=original; }
});

test('unpublished or malformed quizzes do not accept attempts', async () => {
  const original=globalThis.fetch, e=env();
  try {
    globalThis.fetch=async () => new Response(JSON.stringify({schemaVersion:1,quizzes:[]}));
    assert.equal((await worker.fetch(request('GET','/quiz/today'),e)).status,404);
    globalThis.fetch=async () => new Response(JSON.stringify({schemaVersion:1,quizzes:questions().slice(1)}));
    assert.equal((await worker.fetch(request('GET','/quiz/today'),e)).status,503);
    const missingExplanation=questions(); delete missingExplanation[0].explanation;
    globalThis.fetch=async () => new Response(JSON.stringify({schemaVersion:1,quizzes:missingExplanation}));
    assert.equal((await worker.fetch(request('GET','/quiz/today'),e)).status,503);
    assert.equal((await worker.fetch(request('GET','/quiz/today'),{DB:e.DB, QUIZ_CATALOG_URL:'http://insecure/content'})).status,503);
  } finally { globalThis.fetch=original; }
});

test('snapshot updates on schedule and contains only public fields', async () => {
  const original=globalThis.fetch, quiz=questions(), e=env();
  globalThis.fetch=async () => new Response(JSON.stringify({schemaVersion:1,quizzes:quiz}));
  try {
    const {token}=await (await worker.fetch(request('POST','/participants',{username:'Lernender'}),e)).json();
    const visible=await (await worker.fetch(request('GET','/quiz/today'),e)).json();
    assert.deepEqual((await (await worker.fetch(request('GET','/leaderboard'),e)).json()).entries,[]);
    const body={date:visible.date,fingerprint:visible.fingerprint,answers:answerSet(quiz)};
    const statuses=await Promise.all([1,2].map(async()=> (await worker.fetch(request('POST','/quiz/attempt',body,token),e)).status));
    assert.deepEqual(statuses.sort(),[201,409]);
    assert.equal((await (await worker.fetch(request('GET','/leaderboard'),e)).json()).entries.length,0);
    let task; worker.scheduled({},e,{waitUntil(p){task=p;}}); await task;
    const board=await (await worker.fetch(request('GET','/leaderboard'),e)).json();
    assert.deepEqual(board.entries,[{rank:1,username:'Lernender',points:50,quizzes:1}]);
    assert.equal(board.totalParticipants,1);
    assert.ok(board.nextUpdateAtMillis > board.generatedAtMillis);
    assert.ok(!JSON.stringify(board).includes(token));
  } finally { globalThis.fetch=original; }
});

test('changed catalog fingerprint and date prevent stale attempts', async () => {
  const original=globalThis.fetch, quiz=questions(), e=env();
  globalThis.fetch=async () => new Response(JSON.stringify({schemaVersion:1,quizzes:quiz}));
  try {
    const {token}=await (await worker.fetch(request('POST','/participants',{username:'Name'}),e)).json();
    const visible=await (await worker.fetch(request('GET','/quiz/today'),e)).json();
    const body={date:visible.date,fingerprint:visible.fingerprint,answers:answerSet(quiz)};
    quiz[0].explanation='Neue Erklärung';
    assert.equal((await worker.fetch(request('POST','/quiz/attempt',body,token),e)).status,409);
    quiz[0].explanation='Erklärung 0'; quiz[0].correctIndex=1;
    assert.equal((await worker.fetch(request('POST','/quiz/attempt',body,token),e)).status,409);
    assert.equal((await worker.fetch(request('POST','/quiz/attempt',{...body,date:'2000-01-01'},token),e)).status,400);
    assert.equal(e.DB.state.attempts.length,0);
  } finally { globalThis.fetch=original; }
});


test('authenticated deletion removes only own account, attempts and cached ranking', async () => {
  const original=globalThis.fetch, quiz=questions(), e=env();
  globalThis.fetch=async () => new Response(JSON.stringify({schemaVersion:1, quizzes:quiz}));
  try {
    const alice=(await (await worker.fetch(request('POST','/participants',{username:'Alice'}),e)).json()).token;
    const bob=(await (await worker.fetch(request('POST','/participants',{username:'Bob'}),e)).json()).token;
    const visible=await (await worker.fetch(request('GET','/quiz/today'),e)).json();
    const body={date:visible.date,fingerprint:visible.fingerprint,answers:answerSet(quiz)};
    assert.equal((await worker.fetch(request('POST','/quiz/attempt',body,alice),e)).status,201);
    assert.equal((await worker.fetch(request('POST','/quiz/attempt',body,bob),e)).status,201);
    assert.equal((await (await worker.fetch(request('GET','/leaderboard'),e)).json()).entries.length,2);
    assert.equal((await worker.fetch(request('DELETE','/participants/me'),e)).status,401);
    assert.equal((await worker.fetch(request('DELETE','/participants/me',undefined,'0'.repeat(64)),e)).status,401);
    assert.equal((await worker.fetch(request('DELETE','/participants/me',undefined,alice),e)).status,204);
    assert.equal(e.DB.state.participants.length,1); assert.equal(e.DB.state.attempts.length,1);
    assert.equal(e.DB.state.snapshot,null);
    const board=await (await worker.fetch(request('GET','/leaderboard'),e)).json();
    assert.deepEqual(board.entries.map(x => x.username),['Bob']);
    assert.equal((await worker.fetch(request('DELETE','/participants/me',undefined,alice),e)).status,401);
    assert.equal((await worker.fetch(request('POST','/quiz/attempt',body,alice),e)).status,401);
    assert.match((await worker.fetch(request('OPTIONS','/participants/me'),e)).headers.get('Access-Control-Allow-Methods'),/DELETE/);
  } finally { globalThis.fetch=original; }
});

test('snapshot aggregation racing account deletion cannot restore deleted entry', async () => {
  const original=globalThis.fetch, quiz=questions(), e=env();
  globalThis.fetch=async () => new Response(JSON.stringify({schemaVersion:1, quizzes:quiz}));
  try {
    const token=(await (await worker.fetch(request('POST','/participants',{username:'Alice'}),e)).json()).token;
    const visible=await (await worker.fetch(request('GET','/quiz/today'),e)).json();
    const body={date:visible.date,fingerprint:visible.fingerprint,answers:answerSet(quiz)};
    assert.equal((await worker.fetch(request('POST','/quiz/attempt',body,token),e)).status,201);
    e.DB.state.onSnapshotWrite=async () => {
      assert.equal((await worker.fetch(request('DELETE','/participants/me',undefined,token),e)).status,204);
    };
    const board=await (await worker.fetch(request('GET','/leaderboard'),e)).json();
    assert.deepEqual(board.entries,[]); assert.equal(board.totalParticipants,0);
    assert.ok(!e.DB.state.snapshot.includes('Alice'));
  } finally { globalThis.fetch=original; }
});

test('failed deletion transaction does not claim success', async () => {
  const e=env();
  const token=(await (await worker.fetch(request('POST','/participants',{username:'Alice'}),e)).json()).token;
  e.DB.batch=async () => { throw new Error('database unavailable'); };
  assert.equal((await worker.fetch(request('DELETE','/participants/me',undefined,token),e)).status,503);
  assert.equal(e.DB.state.participants.length,1);
});

test('rate controls run before database writes and external quiz fetches', async () => {
  const e=env(), seen=[];
  const block = {async limit({key}) {seen.push(key); return {success:false};}};
  e.REGISTRATION_RATE=block; e.READ_RATE=block; e.AUTH_RATE=block;
  const original=globalThis.fetch;
  let upstreamCalls=0;
  globalThis.fetch=async () => {upstreamCalls++; throw new Error('unexpected');};
  try {
    for (const [method,path,body,token] of [
      ['POST','/participants',{username:'Alice'}], ['GET','/quiz/today'], ['GET','/leaderboard'],
      ['POST','/quiz/attempt',{},'a'.repeat(64)], ['DELETE','/participants/me',undefined,'a'.repeat(64)]
    ]) {
      const result=await worker.fetch(request(method,path,body,token),e);
      assert.equal(result.status,429);
      assert.ok(!JSON.stringify(await result.json()).includes('192.0.2.1'));
    }
    assert.equal(e.DB.state.participants.length,0);
    assert.equal(e.DB.state.attempts.length,0);
    assert.equal(upstreamCalls,0);
    assert.equal(seen.length,5);
    assert.ok(seen.every(key=>/^[0-9a-f]{64}$/.test(key) && !key.includes('192.0.2.1')));
  } finally { globalThis.fetch=original; }
});

test('missing IP, unavailable binding, bad rate response or thrown error fail closed', async () => {
  const e=env();
  const missing=new Request('https://rank.test/participants', {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'Alice'})});
  assert.equal((await worker.fetch(missing,e)).status,503);
  const spoofed=new Request('https://rank.test/participants', {method:'POST',headers:{'X-Forwarded-For':'192.0.2.1'},body:'{}'});
  assert.equal((await worker.fetch(spoofed,e)).status,503);
  for (const limiter of [undefined, {}, {limit:async()=>({})}, {limit:async()=>{throw Error('unavailable');}}]) {
    e.REGISTRATION_RATE=limiter;
    assert.equal((await worker.fetch(request('POST','/participants',{username:'Alice'}),e)).status,503);
  }
  assert.equal(e.DB.state.participants.length,0);
  assert.equal((await worker.fetch(request('GET','/health'),e)).status,200);
  assert.equal((await worker.fetch(request('OPTIONS','/participants'),e)).status,204);
});

test('account limiter hashes bearer token, also protects deletion, and does not key on username', async () => {
  const e=env(), seen=[];
  const token=(await (await worker.fetch(request('POST','/participants',{username:'Alice'}),e)).json()).token;
  e.ACCOUNT_RATE={async limit({key}) { seen.push(key); return {success:false}; }};
  assert.equal((await worker.fetch(request('POST','/quiz/attempt',{},token),e)).status,429);
  assert.equal((await worker.fetch(request('DELETE','/participants/me',undefined,token),e)).status,429);
  assert.equal(e.DB.state.participants.length,1);
  assert.equal(seen.length,2);
  assert.equal(seen[0],seen[1]);
  assert.match(seen[0],/^[0-9a-f]{64}$/);
  assert.notEqual(seen[0],token);
  assert.ok(!seen[0].includes('Alice'));
  e.ACCOUNT_RATE=undefined;
  assert.equal((await worker.fetch(request('DELETE','/participants/me',undefined,token),e)).status,503);
  assert.equal(e.DB.state.participants.length,1);
});
