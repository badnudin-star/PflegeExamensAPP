// Public read-only catalogue; publishing is an authenticated server-side operation.
// This service does not generate medical content or calculate leaderboard scores.
const MAX_BYTES = 8 * 1024 * 1024;
const json = (body, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' }
});

async function sameSecret(a, b) {
  const encoder = new TextEncoder();
  const [x, y] = await Promise.all([crypto.subtle.digest('SHA-256', encoder.encode(a)), crypto.subtle.digest('SHA-256', encoder.encode(b))]);
  const left = new Uint8Array(x), right = new Uint8Array(y);
  let difference = 0;
  for (let i = 0; i < left.length; i++) difference |= left[i] ^ right[i];
  return difference === 0;
}

async function readLimited(request) {
  if (Number(request.headers.get('content-length') || 0) > MAX_BYTES) throw new Error('too large');
  if (!request.body) throw new Error('empty');
  const reader = request.body.getReader();
  const chunks = [];
  let size = 0;
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    size += value.length;
    if (size > MAX_BYTES) { await reader.cancel(); throw new Error('too large'); }
    chunks.push(value);
  }
  const result = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { result.set(chunk, offset); offset += chunk.length; }
  return new TextDecoder('utf-8', { fatal: true }).decode(result);
}

const str = value => typeof value === 'string' && value.trim().length > 0;
const sources = value => Array.isArray(value) && value.length > 0 && value.every(str);
const unique = (array, key) => Array.isArray(array) && array.every(x => x && str(x[key])) && new Set(array.map(x => x[key])).size === array.length;

export function validateCatalog(c) {
  if (!c || c.schemaVersion !== 1 || !Number.isSafeInteger(c.revision) || c.revision < 1) return false;
  if (!unique(c.notes, 'topic') || c.notes.some(n => !str(n.short) || !str(n.balanced) || !str(n.detailed) || !sources(n.sources))) return false;
  if (!unique(c.cards, 'id') || c.cards.some(x => !str(x.topic) || !str(x.front) || !str(x.back) || !sources(x.sources))) return false;
  if (!unique(c.cases, 'id') || c.cases.some(x => !str(x.published) || !str(x.text) || !sources(x.sources) || !Array.isArray(x.diseases) || !x.diseases.length || !x.diseases.every(str) || !Array.isArray(x.questions) || !x.questions.length || x.questions.some(q => !q || !str(q.question) || !str(q.answer) || !sources(q.sources)))) return false;
  if (!unique(c.quizzes, 'id') || c.quizzes.some(q => !str(q.published) || !str(q.topic) || !str(q.question) || !str(q.explanation) || !sources(q.sources) || !Array.isArray(q.options) || q.options.length !== 4 || !q.options.every(str) || new Set(q.options).size !== 4 || !Number.isInteger(q.correctIndex) || q.correctIndex < 0 || q.correctIndex > 3)) return false;
  return true;
}

export default {
  async fetch(request, env) {
    const path = new URL(request.url).pathname;
    if (request.method === 'GET' && path === '/health') return json({ ok: true });
    if (request.method === 'GET' && path === '/content') {
      const row = await env.DB.prepare('SELECT payload FROM content_snapshot WHERE id = 1').first();
      if (!row) return json({ error: 'Noch keine Inhalte veröffentlicht.' }, 404);
      return new Response(row.payload, { headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'public, max-age=300' } });
    }
    if (request.method === 'PUT' && path === '/admin/content') {
      const expected = env.PUBLISH_TOKEN || '';
      const actual = request.headers.get('Authorization')?.replace(/^Bearer /, '') || '';
      if (expected.length < 32 || !await sameSecret(expected, actual)) return json({ error: 'Nicht autorisiert.' }, 401);
      // The publisher must name the exact revision it read (0 means empty DB).
      // A single conditional D1 statement prevents overwriting concurrent updates.
      const match = request.headers.get('If-Match');
      if (!match || !/^(0|[1-9][0-9]*)$/.test(match) || !Number.isSafeInteger(Number(match)))
        return json({ error: 'If-Match mit gelesener Revision erforderlich.' }, 428);
      const expectedRevision = Number(match);
      let raw, data;
      try { raw = await readLimited(request); data = JSON.parse(raw); } catch { return json({ error: 'Ungültiges oder zu großes JSON.' }, 400); }
      if (!validateCatalog(data) || data.revision <= expectedRevision) return json({ error: 'Ungültige Veröffentlichung.' }, 400);
      const result = await env.DB.prepare(`INSERT INTO content_snapshot (id, revision, payload)
        SELECT 1, ?, ? WHERE ? = 0 OR EXISTS (SELECT 1 FROM content_snapshot WHERE id = 1 AND revision = ?)
        ON CONFLICT(id) DO UPDATE SET revision=excluded.revision, payload=excluded.payload
        WHERE content_snapshot.revision = ?`).bind(data.revision, raw, expectedRevision, expectedRevision, expectedRevision).run();
      if (!result.meta?.changes) return json({ error: 'Veröffentlichungsstand hat sich geändert; erneut laden.' }, 409);
      return json({ ok: true, revision: data.revision });
    }
    return json({ error: 'Nicht gefunden.' }, 404);
  }
};
