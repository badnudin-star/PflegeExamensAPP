import {test} from 'node:test';
import assert from 'node:assert/strict';
import {webcrypto} from 'node:crypto';
import worker, {scoreAnswers} from '../src/worker.js';
globalThis.crypto ??= webcrypto;
const day = () => new Intl.DateTimeFormat('en-CA', {timeZone:'Europe/Berlin', year:'numeric', month:'2-digit', day:'2-digit'}).format(new Date());
const questions = () => Array.from({length:50}, (_, i) => ({id:`q-${i}`, published:day(), topic:'Pflege', question:`Frage ${i}?`, options:['a','b','c','d'], correctIndex:i % 4}));
function database() {
  const participants = [], attempts = [], state = {participants, attempts, snapshot:null};
  return {state, prepare(sql) {
    let args=[];
    return {bind(...values) {args=values; return this;}, async run() {
      if (sql.includes('INSERT INTO quiz_participants')) { participants.push({id:args[0], hash:args[1], username:args[2]}); return {meta:{changes:1}}; }
      if (sql.includes('INSERT INTO quiz_attempts')) {
        if (attempts.some(a => a.id === args[0] && a.date === args[1])) return {meta:{changes:0}};
        attempts.push({id:args[0], date:args[1], points:args[2], total:args[3]}); return {meta:{changes:1}};
      }
      if (sql.includes('INSERT INTO quiz_hourly_snapshot')) { state.snapshot=args[0]; return {meta:{changes:1}}; }
      throw Error('unexpected run');
    }, async first() {
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
  const headers = {'Content-Type':'application/json'};
  if (token) headers.Authorization = `Bearer ${token}`;
  return new Request(`https://rank.test${path}`, {method, headers, ...(data === undefined ? {} : {body:JSON.stringify(data)})});
}
function env() { return {DB:database(), QUIZ_CATALOG_URL:'https://catalog.test/content'}; }
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
    assert.equal(visible.questions.length,50); assert.equal(visible.questions[0].correctIndex,undefined);
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
    quiz[0].correctIndex=1;
    assert.equal((await worker.fetch(request('POST','/quiz/attempt',body,token),e)).status,409);
    assert.equal((await worker.fetch(request('POST','/quiz/attempt',{...body,date:'2000-01-01'},token),e)).status,400);
    assert.equal(e.DB.state.attempts.length,0);
  } finally { globalThis.fetch=original; }
});
