import { test } from 'node:test';
import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import worker, { validateCatalog } from '../src/worker.js';
globalThis.crypto ??= webcrypto;
const data = {schemaVersion:1, revision:1, notes:[], cards:[], cases:[], quizzes:[]};
const token = 'x'.repeat(32);
function db() {
  let row;
  return { prepare() { let args; return {bind(...a) { args = a; return this; }, async first() { return row; }, async run() { if (row && args[0] <= row.revision) return {meta:{changes:0}}; row = {revision:args[0], payload:args[1]}; return {meta:{changes:1}}; }}; }};
}
function request(method, path, body, auth = token) { return new Request('https://example.test'+path, {method, headers:{Authorization:'Bearer '+auth}, ...(body === undefined ? {} : {body: JSON.stringify(body)})}); }
test('validates structural and source requirements', () => {
  assert.equal(validateCatalog(data), true);
  assert.equal(validateCatalog({...data, quizzes:[{id:'q', topic:'T', published:'2026-01-01', question:'?', options:['a','b','c','d'], correctIndex:0, explanation:'X', sources:[]}]}), false);
});
test('only authorized, valid, newer snapshots replace the previous version', async () => {
  const env = {DB:db(), PUBLISH_TOKEN:token};
  assert.equal((await worker.fetch(request('PUT','/admin/content',data,'wrong'),env)).status, 401);
  assert.equal((await worker.fetch(request('PUT','/admin/content',data),env)).status, 200);
  assert.equal((await worker.fetch(request('PUT','/admin/content',{...data, revision:2, cards:[{}]}),env)).status, 400);
  assert.equal((await worker.fetch(request('PUT','/admin/content',data),env)).status, 409);
  assert.deepEqual(await (await worker.fetch(request('GET','/content'),env)).json(), data);
});
