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

// Archived quiz days are immutable copies, not a substitute for independent backups.
const MAX_DAY_BYTES = 1024 * 1024;
const MAX_PAGE = 30;
function validDay(day) {
  if (typeof day !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(day)) return false;
  const date = new Date(day + 'T00:00:00Z');
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === day;
}
function berlinToday() {
  const parts = new Intl.DateTimeFormat('en-GB', {timeZone:'Europe/Berlin', year:'numeric', month:'2-digit', day:'2-digit'}).formatToParts(new Date());
  const part = key => parts.find(p => p.type === key)?.value;
  return `${part('year')}-${part('month')}-${part('day')}`;
}
function dayPayload(catalog, day) {
  return JSON.stringify({date:day, quizzes:catalog.quizzes.filter(q => q.published === day)});
}
function ifMatch(request) {
  const value = request.headers.get('If-Match');
  return value && /^(0|[1-9][0-9]*)$/.test(value) && Number.isSafeInteger(Number(value)) ? Number(value) : null;
}
async function authorized(request, env) {
  const expected = env.PUBLISH_TOKEN || '';
  const actual = request.headers.get('Authorization')?.replace(/^Bearer /, '') || '';
  return expected.length >= 32 && await sameSecret(expected, actual);
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
    if (request.method === 'GET' && path === '/quiz-archive') {
      const params = new URL(request.url).searchParams;
      const before = params.get('before');
      const limit = params.get('limit');
      if ([...params.keys()].some(k => !['before', 'limit'].includes(k)) || params.getAll('before').length > 1 ||
          params.getAll('limit').length > 1 || (before !== null && !validDay(before)) ||
          (limit !== null && (!/^[1-9][0-9]*$/.test(limit) || Number(limit) > MAX_PAGE)))
        return json({error:'Ungültige Archivabfrage.'}, 400);
      const count = limit === null ? MAX_PAGE : Number(limit);
      try {
        const result = await env.DB.prepare('SELECT date, question_count FROM quiz_archive WHERE date < ? ORDER BY date DESC LIMIT ?')
          .bind(before || '9999-99-99', count + 1).all();
        const rows = result.results;
        if (!Array.isArray(rows) || rows.some(r => !validDay(r.date) || !Number.isSafeInteger(r.question_count) || r.question_count < 1)) throw new Error('bad archive index');
        const days = rows.slice(0, count).map(row => ({date:row.date, questions:row.question_count}));
        return json({days, nextBefore:rows.length > count ? days.at(-1).date : null});
      } catch { return json({error:'Quizarchiv nicht verfügbar.'}, 503); }
    }
    const archiveDay = path.match(/^\/quiz-archive\/(\d{4}-\d{2}-\d{2})$/)?.[1];
    if (request.method === 'GET' && archiveDay) {
      if (!validDay(archiveDay)) return json({error:'Ungültiges Quizdatum.'}, 400);
      try {
        const row = await env.DB.prepare('SELECT payload FROM quiz_archive WHERE date = ?').bind(archiveDay).first();
        if (!row) return json({error:'Quiztag nicht gefunden.'}, 404);
        return new Response(row.payload, {headers:{'Content-Type':'application/json; charset=utf-8', 'Cache-Control':'no-store'}});
      } catch { return json({error:'Quizarchiv nicht verfügbar.'}, 503); }
    }
    const archiveAdminDay = path.match(/^\/admin\/quiz-archive\/(\d{4}-\d{2}-\d{2})$/)?.[1];
    if (request.method === 'POST' && archiveAdminDay) {
      if (!await authorized(request, env)) return json({error:'Nicht autorisiert.'}, 401);
      if (!validDay(archiveAdminDay)) return json({error:'Ungültiges Quizdatum.'}, 400);
      const expectedRevision = ifMatch(request);
      if (expectedRevision === null || expectedRevision === 0) return json({error:'If-Match mit gelesener Revision erforderlich.'}, 428);
      try {
        const row = await env.DB.prepare('SELECT revision, payload FROM content_snapshot WHERE id = 1').first();
        if (!row || row.revision !== expectedRevision) return json({error:'Veröffentlichungsstand hat sich geändert.'}, 409);
        const catalog = JSON.parse(row.payload);
        if (!validateCatalog(catalog) || catalog.revision !== row.revision) throw new Error('bad snapshot');
        const questions = catalog.quizzes.filter(q => q.published === archiveAdminDay);
        if (!questions.length) return json({error:'Quiztag nicht im Katalog.'}, 404);
        const payload = dayPayload(catalog, archiveAdminDay);
        if (new TextEncoder().encode(payload).length > MAX_DAY_BYTES) return json({error:'Quiztag zu groß.'}, 400);
        const result = await env.DB.prepare(`INSERT INTO quiz_archive(date, question_count, payload)
          SELECT ?, ?, ? WHERE EXISTS (SELECT 1 FROM content_snapshot WHERE id = 1 AND revision = ?)
          ON CONFLICT(date) DO NOTHING`).bind(archiveAdminDay, questions.length, payload, expectedRevision).run();
        if (result.meta?.changes) return json({ok:true, date:archiveAdminDay, questions:questions.length}, 201);
        const existing = await env.DB.prepare('SELECT question_count, payload FROM quiz_archive WHERE date = ?').bind(archiveAdminDay).first();
        const current = await env.DB.prepare('SELECT revision, payload FROM content_snapshot WHERE id = 1').first();
        if (current?.revision === expectedRevision && existing?.payload === payload && existing?.question_count === questions.length)
          return json({ok:true, date:archiveAdminDay, questions:questions.length});
        return json({error:'Archiv oder Katalog hat sich geändert; keine Überschreibung.'}, 409);
      } catch { return json({error:'Quizarchiv nicht verfügbar.'}, 503); }
    }
    if (request.method === 'PUT' && path === '/admin/content') {
      if (!await authorized(request, env)) return json({ error: 'Nicht autorisiert.' }, 401);
      // The publisher must name the exact revision it read (0 means empty DB).
      // A single conditional D1 statement prevents overwriting concurrent updates.
      const expectedRevision = ifMatch(request);
      if (expectedRevision === null) return json({ error: 'If-Match mit gelesener Revision erforderlich.' }, 428);
      let raw, data;
      try { raw = await readLimited(request); data = JSON.parse(raw); } catch { return json({ error: 'Ungültiges oder zu großes JSON.' }, 400); }
      if (!validateCatalog(data) || data.revision <= expectedRevision) return json({ error: 'Ungültige Veröffentlichung.' }, 400);
      // Removing quiz questions is permitted only for whole past days backed by
      // an exact immutable archive copy. No notes, cards or cases are touched here.
      // The final revision CAS protects this validation from concurrent publishers.
      try {
        const current = await env.DB.prepare('SELECT revision, payload FROM content_snapshot WHERE id = 1').first();
        if ((current?.revision ?? 0) !== expectedRevision) return json({error:'Veröffentlichungsstand hat sich geändert; erneut laden.'}, 409);
        if (current) {
          const old = JSON.parse(current.payload);
          if (!validateCatalog(old) || old.revision !== current.revision) throw new Error('bad snapshot');
          const nextDates = new Map(data.quizzes.map(q => [q.id, q.published]));
          const nextIds = new Set(nextDates.keys());
          const removedDays = new Set(old.quizzes.filter(q => nextDates.get(q.id) !== q.published).map(q => q.published));
          for (const day of removedDays) {
            if (!validDay(day) || day >= berlinToday() || data.quizzes.some(q => q.published === day) ||
                old.quizzes.some(q => q.published === day && nextIds.has(q.id)))
              return json({error:'Nur vollständig archivierte vergangene Quiztage können entfernt werden.'}, 409);
            const archive = await env.DB.prepare('SELECT question_count, payload FROM quiz_archive WHERE date = ?').bind(day).first();
            const questions = old.quizzes.filter(q => q.published === day);
            if (!archive || archive.question_count !== questions.length || archive.payload !== dayPayload(old, day))
              return json({error:'Unveränderte Archivkopie fehlt; keine Entfernung.'}, 409);
          }
        }
      } catch { return json({error:'Katalog oder Archivprüfung nicht verfügbar.'}, 503); }
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
