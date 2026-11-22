import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {Miniflare, convertV4MiniflareOptions} from 'miniflare';

const root = new URL('../', import.meta.url);
const file = name => readFileSync(new URL(name, root), 'utf8');
const token = 'synthetic-publish-token-not-a-secret';
const oldDay = '2020-01-01', nextDay = '2020-01-02';
const question = (id, date) => ({id, published:date, topic:'Thema', question:`Frage ${id}`,
  options:['A','B','C','D'], correctIndex:0, explanation:'Erklärung', sources:['Testquelle']});
const catalog = (revision, quizzes) => ({schemaVersion:1, revision, notes:[], cards:[], cases:[], quizzes});
function statements(source) {
  return source.replace(/^\s*--.*$/gm, '').split(';').map(s=>s.trim()).filter(Boolean).map(sql=>({sql}));
}
function runtime() {
  return new Miniflare(convertV4MiniflareOptions({cf:false, workers:[{name:'content', modules:[{type:'ESModule',path:fileURLToPath(new URL('fixtures/miniflare-harness.mjs',root))},
    {type:'ESModule',path:fileURLToPath(new URL('src/worker.js',root))}], modulesRoot:fileURLToPath(root),
    compatibilityDate:'2026-11-01', d1Databases:['DB'], bindings:{PUBLISH_TOKEN:token},
    outboundService:'blocked'}, {name:'blocked', modules:true, compatibilityDate:'2026-11-01',
      script:'export default {fetch() {return new Response("blocked", {status:403})}}'}]}));
}
async function sql(mf, statements) {
  const response = await mf.dispatchFetch('https://content.test/__test/sql', {method:'POST',body:JSON.stringify({statements})});
  const result = await response.json();
  assert.equal(response.status, 200, JSON.stringify(result));
  return result;
}
async function request(mf, method, path, body, revision, auth=token) {
  const headers = {'Content-Type':'application/json'};
  if (auth !== null) headers.Authorization = 'Bearer '+auth;
  if (revision !== undefined) headers['If-Match'] = String(revision);
  return mf.dispatchFetch('https://content.test'+path, {method, headers,
    ...(body === undefined ? {} : {body:JSON.stringify(body)})});
}

test('Miniflare D1: archive copies, safe pruning, pagination and revision conflicts', {timeout:30000}, async () => {
  const mf=runtime();
  try {
    await sql(mf,statements(file('schema.sql')));
    const quizzes=[question('a',oldDay),question('b',oldDay),question('c',nextDay)];
    assert.equal((await request(mf,'GET','/content')).status,404);
    assert.equal((await request(mf,'PUT','/admin/content',catalog(1,quizzes),0,'wrong')).status,401);
    assert.equal((await request(mf,'PUT','/admin/content',catalog(1,quizzes))).status,428);
    assert.equal((await request(mf,'PUT','/admin/content',catalog(1,quizzes),0)).status,200);
    assert.equal((await request(mf,'POST',`/admin/quiz-archive/${oldDay}`,undefined,2)).status,409);
    assert.equal((await request(mf,'PUT','/admin/content',catalog(2,[quizzes[2]]),1)).status,409);
    assert.equal((await request(mf,'POST',`/admin/quiz-archive/${oldDay}`,undefined,1)).status,201);
    assert.equal((await request(mf,'POST',`/admin/quiz-archive/${oldDay}`,undefined,1)).status,200);
    assert.equal((await request(mf,'POST',`/admin/quiz-archive/${nextDay}`,undefined,1)).status,201);
    assert.deepEqual(await (await request(mf,'GET',`/quiz-archive/${oldDay}`)).json(),{date:oldDay,quizzes:quizzes.slice(0,2)});
    const page1=await (await request(mf,'GET','/quiz-archive?limit=1')).json();
    assert.deepEqual(page1,{days:[{date:nextDay,questions:1}],nextBefore:nextDay});
    assert.deepEqual(await (await request(mf,'GET',`/quiz-archive?limit=1&before=${nextDay}`)).json(),
      {days:[{date:oldDay,questions:2}],nextBefore:null});
    assert.equal((await request(mf,'GET','/quiz-archive?before=2020-02-30')).status,400);
    assert.equal((await request(mf,'PUT','/admin/content',catalog(2,quizzes.slice(1)),1)).status,409);
    assert.equal((await request(mf,'PUT','/admin/content',catalog(2,[quizzes[2]]),1)).status,200);
    assert.deepEqual((await (await request(mf,'GET','/content')).json()).quizzes,[quizzes[2]]);
    assert.deepEqual((await (await request(mf,'GET',`/quiz-archive/${oldDay}`)).json()).quizzes,quizzes.slice(0,2));
    const changed=[{...quizzes[0],question:'Korrigiert'},quizzes[1],quizzes[2]];
    assert.equal((await request(mf,'PUT','/admin/content',catalog(3,changed),2)).status,200);
    assert.equal((await request(mf,'POST',`/admin/quiz-archive/${oldDay}`,undefined,3)).status,409);
    assert.equal((await request(mf,'PUT','/admin/content',catalog(4,[quizzes[2]]),3)).status,409);
    assert.deepEqual((await (await request(mf,'GET',`/quiz-archive/${oldDay}`)).json()).quizzes,quizzes.slice(0,2));
  } finally {await mf.dispose();}
});

test('Miniflare D1: old schema fails closed, archive migration preserves data and is repeatable', {timeout:30000}, async () => {
  const mf=runtime();
  try {
    await sql(mf,[{sql:'CREATE TABLE content_snapshot (id INTEGER PRIMARY KEY CHECK (id=1), revision INTEGER NOT NULL, payload TEXT NOT NULL)'}]);
    const original=catalog(1,[question('legacy',oldDay)]);
    assert.equal((await request(mf,'PUT','/admin/content',original,0)).status,200);
    assert.equal((await request(mf,'GET','/quiz-archive')).status,503);
    assert.equal((await request(mf,'POST',`/admin/quiz-archive/${oldDay}`,undefined,1)).status,503);
    assert.equal((await request(mf,'PUT','/admin/content',catalog(2,[]),1)).status,503);
    const migration=statements(file('migrations/0001_quiz_archive.sql'));
    await sql(mf,migration);
    assert.equal((await request(mf,'POST',`/admin/quiz-archive/${oldDay}`,undefined,1)).status,201);
    await sql(mf,migration);
    assert.deepEqual(await (await request(mf,'GET','/content')).json(),original);
    assert.deepEqual((await (await request(mf,'GET',`/quiz-archive/${oldDay}`)).json()).quizzes,original.quizzes);
    assert.equal((await request(mf,'PUT','/admin/content',catalog(2,[]),1)).status,200);
  } finally {await mf.dispose();}
});

test('Miniflare D1: simultaneous publishers cannot overwrite one another', {timeout:30000}, async () => {
  const mf=runtime();
  try {
    await sql(mf,statements(file('schema.sql')));
    assert.equal((await request(mf,'PUT','/admin/content',catalog(1,[]),0)).status,200);
    const candidates=[catalog(2,[question('a',oldDay)]),catalog(3,[question('b',nextDay)])];
    const results=await Promise.all(candidates.map(value=>request(mf,'PUT','/admin/content',value,1)));
    assert.deepEqual(results.map(x=>x.status).sort(),[200,409]);
    const winner=candidates[results.findIndex(x=>x.status===200)];
    assert.deepEqual(await (await request(mf,'GET','/content')).json(),winner);
  } finally {await mf.dispose();}
});

test('Miniflare D1: concurrent archive and catalog correction never archive a newer revision as an older one', {timeout:30000}, async () => {
  const mf=runtime();
  try {
    await sql(mf,statements(file('schema.sql')));
    const original=catalog(1,[question('a',oldDay)]);
    const corrected=catalog(2,[{...original.quizzes[0],question:'Korrigiert'}]);
    assert.equal((await request(mf,'PUT','/admin/content',original,0)).status,200);
    const [archive,publish]=await Promise.all([
      request(mf,'POST',`/admin/quiz-archive/${oldDay}`,undefined,1),
      request(mf,'PUT','/admin/content',corrected,1)
    ]);
    assert.equal(publish.status,200);
    assert.ok([201,409].includes(archive.status));
    const archived=await request(mf,'GET',`/quiz-archive/${oldDay}`);
    if (archive.status===201) {
      assert.equal(archived.status,200);
      assert.deepEqual((await archived.json()).quizzes,original.quizzes);
      assert.equal((await request(mf,'POST',`/admin/quiz-archive/${oldDay}`,undefined,2)).status,409);
    } else assert.equal(archived.status,404);
    assert.deepEqual(await (await request(mf,'GET','/content')).json(),corrected);
    // Either the exact old archive differs from the correction, or there is no archive.
    assert.equal((await request(mf,'PUT','/admin/content',catalog(3,[]),2)).status,409);
  } finally {await mf.dispose();}
});

test('Miniflare: authorization, revision, input and future-day safeguards', {timeout:30000}, async () => {
  const mf=runtime();
  try {
    await sql(mf,statements(file('schema.sql')));
    const future='2099-01-01', content=catalog(1,[question('future',future)]);
    assert.equal((await request(mf,'PUT','/admin/content',content,0,null)).status,401);
    assert.equal((await request(mf,'PUT','/admin/content',content,'00')).status,428);
    assert.equal((await request(mf,'PUT','/admin/content',catalog(1,[{...content.quizzes[0],sources:[]}]),0)).status,400);
    assert.equal((await request(mf,'PUT','/admin/content',content,0)).status,200);
    assert.equal((await request(mf,'POST',`/admin/quiz-archive/${future}`,undefined,1,'wrong')).status,401);
    assert.equal((await request(mf,'POST',`/admin/quiz-archive/${future}`)).status,428);
    assert.equal((await request(mf,'POST',`/admin/quiz-archive/${future}`,undefined,1)).status,201);
    assert.equal((await request(mf,'PUT','/admin/content',catalog(2,[]),1)).status,409);
    assert.equal((await request(mf,'GET','/quiz-archive?limit=1&limit=2')).status,400);
    assert.equal((await request(mf,'GET','/quiz-archive?limit=31')).status,400);
    assert.equal((await request(mf,'GET','/quiz-archive/2020-02-30')).status,400);
    assert.equal((await request(mf,'GET','/quiz-archive/2019-01-01')).status,404);
    assert.equal((await request(mf,'DELETE',`/quiz-archive/${future}`)).status,404);
    assert.deepEqual(await (await request(mf,'GET','/content')).json(),content);
  } finally {await mf.dispose();}
});

test('Python archive tools and worker perform a complete isolated backup/prune/restore flow', {timeout:60000}, async () => {
  const {createServer} = await import('node:http');
  const {spawn} = await import('node:child_process');
  const {once} = await import('node:events');
  const mf = runtime();
  let server;
  try {
    await sql(mf,statements(file('schema.sql')));
    // The bridge exposes only production endpoints, on loopback, for synthetic data.
    server=createServer(async (req,res) => {
      try {
        const url = new URL(req.url, 'https://content.test');
        if (!['/content','/quiz-archive/2020-01-01','/admin/quiz-archive/2020-01-01','/admin/content'].includes(url.pathname)) {
          res.writeHead(404).end(); return;
        }
        const chunks=[];
        for await (const chunk of req) chunks.push(chunk);
        const body=Buffer.concat(chunks);
        const headers={};
        for (const name of ['authorization','content-type','if-match','cache-control'])
          if (req.headers[name]) headers[name]=req.headers[name];
        const response=await mf.dispatchFetch(url.toString(), {method:req.method,headers,...(body.length ? {body} : {})});
        res.writeHead(response.status, {'content-type':response.headers.get('content-type') || 'application/json'});
        res.end(Buffer.from(await response.arrayBuffer()));
      } catch {
        if (!res.headersSent) res.writeHead(500);
        res.end();
      }
    });
    server.listen(0,'127.0.0.1');
    await once(server,'listening');
    const python=spawn('python3',[fileURLToPath(new URL('fixtures/archive-flow.py',root)),String(server.address().port)],
      {env:{PATH:process.env.PATH, PYTHONIOENCODING:'utf-8'},stdio:['ignore','pipe','pipe'],timeout:35000});
    let stdout='',stderr='';
    python.stdout.on('data',x=>{stdout+=x;}); python.stderr.on('data',x=>{stderr+=x;});
    const [code]=await once(python,'close');
    assert.equal(code,0,stderr);
    assert.match(stdout,/synthetic archive flow passed/);
  } finally {
    if (server) await new Promise((resolve,reject)=>server.close(error=>error ? reject(error) : resolve()));
    await mf.dispose();
  }
});
