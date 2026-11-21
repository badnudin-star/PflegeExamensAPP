// Draft quiz-only ranking. No client-submitted XP, streak or direct score is accepted.
const HOUR = 60 * 60 * 1000;
const MAX_BODY = 64 * 1024;
const MAX_CATALOG = 8 * 1024 * 1024;
const QUIZ_SIZE = 50;
const headers = {
  'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store',
  'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, Authorization'
};
const json = (value, status = 200) => new Response(JSON.stringify(value), {status, headers});
const hex = bytes => Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
async function digest(value) {
  return hex(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value))));
}
// Approximate, per-Cloudflare-location limits; not identity verification.
// Only Cloudflare's connecting-IP header is used, never client forwarding headers.
async function allowRate(limiter, key) {
  if (!limiter || typeof limiter.limit !== 'function') throw new Error('rate limiting unavailable');
  const result = await limiter.limit({key});
  if (typeof result?.success !== 'boolean') throw new Error('rate limiting unavailable');
  return result.success;
}
async function protectRequest(request, env, path) {
  let limiter, needsAccountLimit = false;
  if (request.method === 'POST' && path === '/participants') limiter = env.REGISTRATION_RATE;
  else if (request.method === 'GET' && (path === '/quiz/today' || path === '/leaderboard')) limiter = env.READ_RATE;
  else if ((request.method === 'POST' && path === '/quiz/attempt') ||
           (request.method === 'DELETE' && path === '/participants/me')) {
    limiter = env.AUTH_RATE;
    needsAccountLimit = true;
  }
  else return true;
  const ip = request.headers.get('CF-Connecting-IP');
  if (!ip || ip.length > 45 || ip.length < 2 || !/^[0-9a-f:.]+$/i.test(ip) || !/[.:]/.test(ip))
    throw new Error('client address unavailable');
  // Hash is pseudonymous, not anonymous; Cloudflare processes the original IP.
  if (!await allowRate(limiter, await digest('ip:' + ip.toLowerCase()))) return false;
  if (needsAccountLimit) {
    const token = request.headers.get('Authorization')?.match(/^Bearer ([0-9a-f]{64})$/)?.[1];
    if (token && !await allowRate(env.ACCOUNT_RATE, await digest('token:' + token))) return false;
  }
  return true;
}
function today() {
  return new Intl.DateTimeFormat('en-CA', {timeZone: 'Europe/Berlin', year: 'numeric', month: '2-digit', day: '2-digit'}).format(new Date());
}
function validUsername(value) {
  return typeof value === 'string' && value === value.trim() && value.length >= 2 && value.length <= 24 &&
    /^[\p{L}\p{N}_ .-]+$/u.test(value) && !/\s{2}/u.test(value);
}
async function limited(request, max) {
  const declared = Number(request.headers.get('content-length') || 0);
  if (declared > max || !request.body) throw new Error('invalid body');
  const reader = request.body.getReader(), chunks = [];
  let size = 0;
  for (;;) {
    const {value, done} = await reader.read();
    if (done) break;
    size += value.length;
    if (size > max) { await reader.cancel(); throw new Error('too large'); }
    chunks.push(value);
  }
  const bytes = new Uint8Array(size); let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  return JSON.parse(new TextDecoder('utf-8', {fatal:true}).decode(bytes));
}
function catalogUrl(raw) {
  try {
    const u = new URL(raw);
    if (u.protocol !== 'https:' || !u.hostname || u.username || u.password || u.pathname !== '/content' || u.search || u.hash) return null;
    return u.toString();
  } catch { return null; }
}
async function loadQuiz(env, date) {
  const url = catalogUrl(env.QUIZ_CATALOG_URL);
  if (!url) throw new Error('catalog not configured');
  const response = await fetch(url, {headers: {'Cache-Control':'no-cache'}, redirect:'error'});
  if (response.status !== 200) throw new Error('catalog unavailable');
  const catalog = await limited(response, MAX_CATALOG);
  if (catalog.schemaVersion !== 1 || !Array.isArray(catalog.quizzes)) throw new Error('bad catalog');
  const questions = catalog.quizzes.filter(q => q && q.published === date);
  if (!questions.length) return null;
  if (questions.length !== QUIZ_SIZE || new Set(questions.map(q => q.id)).size !== QUIZ_SIZE ||
      questions.some(q => typeof q.id !== 'string' || !q.id.trim() || typeof q.topic !== 'string' || !q.topic.trim() ||
        typeof q.question !== 'string' || !q.question.trim() || !Array.isArray(q.options) || q.options.length !== 4 ||
        q.options.some(o => typeof o !== 'string' || !o.trim()) || new Set(q.options).size !== 4 ||
        !Number.isInteger(q.correctIndex) || q.correctIndex < 0 || q.correctIndex > 3)) throw new Error('bad quiz');
  const frozen = questions.map(q => ({id:q.id, topic:q.topic, question:q.question, options:q.options, correctIndex:q.correctIndex}));
  return {questions:frozen, fingerprint:await digest(JSON.stringify(frozen))};
}
export function scoreAnswers(questions, answers) {
  if (!Array.isArray(answers) || answers.length !== questions.length) throw new Error('invalid answers');
  const selected = new Map();
  for (const answer of answers) {
    if (!answer || typeof answer.id !== 'string' || selected.has(answer.id) ||
        !Number.isInteger(answer.option) || answer.option < 0 || answer.option > 3) throw new Error('invalid answers');
    selected.set(answer.id, answer.option);
  }
  if (questions.some(q => !selected.has(q.id))) throw new Error('invalid answers');
  return questions.reduce((score, q) => score + Number(selected.get(q.id) === q.correctIndex), 0);
}
async function participant(request, env) {
  const token = request.headers.get('Authorization')?.match(/^Bearer ([0-9a-f]{64})$/)?.[1];
  if (!token) return null;
  return env.DB.prepare('SELECT id FROM quiz_participants WHERE token_hash = ?').bind(await digest(token)).first();
}
async function snapshot(env, now = Date.now()) {
  // If deletion overlaps aggregation, never persist or return a stale snapshot.
  // The conditional write uses the generation bumped atomically on deletion.
  for (let retry = 0; retry < 3; retry++) {
    const generation = await env.DB.prepare('SELECT generation FROM quiz_snapshot_generation WHERE id = 1').first();
    if (!generation || !Number.isSafeInteger(generation.generation)) throw new Error('missing generation');
    const result = await env.DB.prepare(`SELECT p.username, SUM(a.correct) AS points, COUNT(*) AS quizzes
      FROM quiz_attempts a JOIN quiz_participants p ON p.id = a.participant_id
      GROUP BY p.id, p.username ORDER BY points DESC, MIN(a.submitted_at) ASC, p.id ASC LIMIT 100`).all();
    const count = await env.DB.prepare('SELECT COUNT(DISTINCT participant_id) AS count FROM quiz_attempts').first();
    const value = {generatedAtMillis:now, nextUpdateAtMillis:(Math.floor(now / HOUR) + 1) * HOUR,
      totalParticipants:Number(count?.count || 0),
      entries:(result.results || []).map((row, index) => ({rank:index+1, username:row.username, points:Number(row.points), quizzes:Number(row.quizzes)}))};
    const saved = await env.DB.prepare(`INSERT INTO quiz_hourly_snapshot(id, payload_json, generated_at)
      SELECT 1, ?, ? WHERE (SELECT generation FROM quiz_snapshot_generation WHERE id = 1) = ?
      ON CONFLICT(id) DO UPDATE SET payload_json = excluded.payload_json, generated_at = excluded.generated_at`)
      .bind(JSON.stringify(value), now, generation.generation).run();
    if (saved.meta?.changes) return value;
  }
  throw new Error('snapshot changed during deletion');
}

export default {
  async fetch(request, env) {
    const path = new URL(request.url).pathname;
    if (request.method === 'OPTIONS') return new Response(null, {status:204, headers});
    if (request.method === 'GET' && path === '/health') return json({ok:true, service:'quiz-ranking-draft'});
    if (!env.DB) return json({error:'Ranking nicht konfiguriert.'}, 503);
    try {
      if (!await protectRequest(request, env, path)) return json({error:'Zu viele Anfragen. Bitte später erneut versuchen.'}, 429);
      if (request.method === 'POST' && path === '/participants') {
        let data; try { data = await limited(request, MAX_BODY); } catch { return json({error:'Ungültige Anfrage.'}, 400); }
        if (!validUsername(data?.username) || Object.keys(data).length !== 1) return json({error:'Pseudonym ungültig.'}, 400);
        const token = hex(crypto.getRandomValues(new Uint8Array(32)));
        const id = crypto.randomUUID();
        await env.DB.prepare('INSERT INTO quiz_participants(id, token_hash, username, created_at) VALUES(?, ?, ?, ?)')
          .bind(id, await digest(token), data.username, Date.now()).run();
        return json({token}, 201);
      }
      if (request.method === 'DELETE' && path === '/participants/me') {
        const user = await participant(request, env);
        if (!user) return json({error:'Nicht autorisiert.'}, 401);
        // D1 batch is transactional. Invalidate the public snapshot and advance
        // the generation in the same transaction as deleting the account.
        await env.DB.batch([
          env.DB.prepare('DELETE FROM quiz_attempts WHERE participant_id = ?').bind(user.id),
          env.DB.prepare('DELETE FROM quiz_participants WHERE id = ?').bind(user.id),
          env.DB.prepare('DELETE FROM quiz_hourly_snapshot WHERE id = 1'),
          env.DB.prepare('UPDATE quiz_snapshot_generation SET generation = generation + 1 WHERE id = 1')
        ]);
        return new Response(null, {status:204, headers});
      }
      if (request.method === 'GET' && path === '/quiz/today') {
        const day = today(), quiz = await loadQuiz(env, day);
        if (!quiz) return json({error:'Heute ist noch kein Quiz veröffentlicht.'}, 404);
        return json({date:day, fingerprint:quiz.fingerprint, questions:quiz.questions.map(({correctIndex, ...q}) => q)});
      }
      if (request.method === 'POST' && path === '/quiz/attempt') {
        const user = await participant(request, env);
        if (!user) return json({error:'Nicht autorisiert.'}, 401);
        let data; try { data = await limited(request, MAX_BODY); } catch { return json({error:'Ungültige Anfrage.'}, 400); }
        const day = today();
        if (!data || data.date !== day || typeof data.fingerprint !== 'string' || Object.keys(data).sort().join(',') !== 'answers,date,fingerprint')
          return json({error:'Nur das aktuelle Tagesquiz kann gewertet werden.'}, 400);
        const quiz = await loadQuiz(env, day);
        if (!quiz) return json({error:'Heute ist noch kein Quiz veröffentlicht.'}, 404);
        if (quiz.fingerprint !== data.fingerprint) return json({error:'Quiz wurde geändert. Bitte neu laden.'}, 409);
        let score; try { score = scoreAnswers(quiz.questions, data.answers); } catch { return json({error:'Antworten unvollständig oder ungültig.'}, 400); }
        const saved = await env.DB.prepare(`INSERT INTO quiz_attempts(participant_id, quiz_date, correct, total, submitted_at)
          VALUES(?, ?, ?, ?, ?) ON CONFLICT(participant_id, quiz_date) DO NOTHING`)
          .bind(user.id, day, score, quiz.questions.length, Date.now()).run();
        if (!saved.meta?.changes) return json({error:'Dieses Tagesquiz wurde bereits gewertet.'}, 409);
        return json({date:day, correct:score, total:quiz.questions.length}, 201);
      }
      if (request.method === 'GET' && path === '/leaderboard') {
        const row = await env.DB.prepare('SELECT payload_json FROM quiz_hourly_snapshot WHERE id = 1').first();
        return json(row ? JSON.parse(row.payload_json) : await snapshot(env));
      }
      return json({error:'Nicht gefunden.'}, 404);
    } catch {
      return json({error:'Quiz-Ranking vorübergehend nicht verfügbar.'}, 503);
    }
  },
  async scheduled(_event, env, ctx) { ctx.waitUntil(snapshot(env)); }
};
