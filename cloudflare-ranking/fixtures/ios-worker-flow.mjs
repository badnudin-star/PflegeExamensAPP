// Local-only bridge for the macOS Swift smoke binary. NEVER deploy this script.
// Every account, question, database and credential is synthetic and in-memory.
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {readFileSync} from 'node:fs';
import {createServer} from 'node:http';
import {fileURLToPath} from 'node:url';
import {Miniflare} from 'miniflare';

const root = new URL('../', import.meta.url);
const file = name => readFileSync(new URL(name, root), 'utf8');
function statements(source) {
  let current = '', trigger = false; const result = [];
  for (const line of source.split('\n')) {
    if (/^\s*--/.test(line) || !line.trim()) continue;
    current += `${line}\n`;
    if (/^\s*CREATE TRIGGER/i.test(line)) trigger = true;
    if ((!trigger && /;\s*$/.test(line)) || (trigger && /END;\s*$/.test(line))) {
      result.push(current.trim()); current = ''; trigger = false;
    }
  }
  assert.equal(current.trim(), '');
  return result;
}
const rates = Object.fromEntries(['REGISTRATION_RATE','READ_RATE','AUTH_RATE','ACCOUNT_RATE'].map((name, i) =>
  [name, {namespace_id:String(82001+i), simple:{limit:1000,period:60}}]));
const mf = new Miniflare({workers:[
  {name:'ranking',modules:true,modulesRules:[{type:'ESModule',include:['**/*.js','**/*.mjs']}],
    scriptPath:fileURLToPath(new URL('fixtures/miniflare-harness.mjs',root)), compatibilityDate:'2025-11-01', cf:false,
    d1Databases:['DB','DELETION_JOURNAL'],ratelimits:rates,bindings:{QUIZ_CATALOG_URL:'https://catalog.test/content',QUIZ_CATALOG_TRANSPORT:'service'},
    serviceBindings:{QUIZ_CATALOG_SERVICE:'catalog'},outboundService:{network:{allow:[]}}},
  {name:'catalog',modules:true,scriptPath:fileURLToPath(new URL('fixtures/miniflare-catalog.mjs',root)),
    compatibilityDate:'2025-11-01',cf:false,bindings:{CATALOG_STATUS:'ok'},outboundService:{network:{allow:[]}}}
]});
let server;
try {
  const db = await mf.getD1Database('DB'), journal = await mf.getD1Database('DELETION_JOURNAL');
  for (const sql of statements(file('schema.sql'))) await db.prepare(sql).run();
  for (const sql of statements(file('journal-schema.sql'))) await journal.prepare(sql).run();
  for (const sql of statements(file('migrations/0004_journal_delete_guard.sql'))) await db.prepare(sql).run();
  const allowed = new Set(['/quiz/today','/participants','/participants/me','/quiz/attempt','/quiz/attempt/result','/quiz/attempt/close']);
  server = createServer(async (req,res) => {
    try {
      const url = new URL(req.url,'https://ranking.test');
      if (!allowed.has(url.pathname)) { res.writeHead(404).end(); return; }
      const chunks=[]; let size=0;
      for await (const chunk of req) { size+=chunk.length; if (size>65536) throw Error('oversized'); chunks.push(chunk); }
      const body=Buffer.concat(chunks), headers={'CF-Connecting-IP':'192.0.2.1'};
      for (const key of ['authorization','content-type','accept']) if (req.headers[key]) headers[key]=req.headers[key];
      const response=await mf.dispatchFetch(url.toString(),{method:req.method,headers,...(body.length ? {body} : {})});
      const outgoing={};
      for (const key of ['content-type','x-quiz-deletion-receipt']) if (response.headers.has(key)) outgoing[key]=response.headers.get(key);
      res.writeHead(response.status,outgoing); res.end(Buffer.from(await response.arrayBuffer()));
    } catch { if (!res.headersSent) res.writeHead(500); res.end(); }
  });
  server.listen(0,'127.0.0.1'); await once(server,'listening');
  assert.ok(process.argv[2]);
  const child=spawn(process.argv[2],['--worker-test',String(server.address().port)],
    {stdio:['ignore','pipe','pipe'], timeout:40000, env:{PATH:process.env.PATH}});
  let stdout='',stderr=''; child.stdout.on('data',v=>stdout+=v); child.stderr.on('data',v=>stderr+=v);
  const [code]=await once(child,'close');
  assert.equal(code,0,stderr); assert.match(stdout,/Swift iOS synthetic Worker flow passed/);
  const counts=await db.prepare('SELECT (SELECT count(*) FROM quiz_participants) AS users, (SELECT count(*) FROM quiz_attempts) AS attempts, (SELECT count(*) FROM quiz_deleted_tokens) AS barriers').first();
  const journalCount=await journal.prepare('SELECT count(*) AS n FROM deletion_journal').first();
  assert.deepEqual(counts,{users:0,attempts:0,barriers:1}); assert.equal(journalCount.n,1);
  console.log('iOS-Klient und Worker mit synthetischem Quiz, isolierten D1-Daten und separatem Löschjournal geprüft.');
} finally {
  if (server) await new Promise((resolve,reject)=>server.close(error=>error ? reject(error) : resolve()));
  await mf.dispose();
}
