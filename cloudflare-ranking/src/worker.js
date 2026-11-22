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
const deletedReceipt = () => new Response(null, {status:204, headers:{...headers, 'X-Quiz-Deletion-Receipt':'v1'}});
const hex = bytes => Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
async function digestBytes(bytes) {
  return hex(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)));
}
async function digest(value) { return digestBytes(new TextEncoder().encode(value)); }
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
  if (request.method === 'POST' && path === '/participants') {
    limiter = env.REGISTRATION_RATE;
    needsAccountLimit = true;
  }
  else if (request.method === 'GET' && (path === '/quiz/today' || path === '/leaderboard')) limiter = env.READ_RATE;
  else if ((request.method === 'POST' && (path === '/quiz/attempt' || path === '/quiz/attempt/close')) ||
           (request.method === 'GET' && path === '/quiz/attempt/result') ||
           (request.method === 'GET' && path === '/participants/me') ||
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
async function limited(request, max, includeHash = false) {
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
  const value = JSON.parse(new TextDecoder('utf-8', {fatal:true}).decode(bytes));
  return includeHash ? {value, bodyHash:await digestBytes(bytes)} : value;
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
        typeof q.question !== 'string' || !q.question.trim() || typeof q.explanation !== 'string' || !q.explanation.trim() ||
        !Array.isArray(q.options) || q.options.length !== 4 ||
        q.options.some(o => typeof o !== 'string' || !o.trim()) || new Set(q.options).size !== 4 ||
        !Number.isInteger(q.correctIndex) || q.correctIndex < 0 || q.correctIndex > 3)) throw new Error('bad quiz');
  // Answers and explanations are intentionally public for immediate feedback.
  // Including them in the fingerprint invalidates a session when they change.
  const frozen = questions.map(q => ({id:q.id, topic:q.topic, question:q.question, options:q.options, correctIndex:q.correctIndex, explanation:q.explanation}));
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
function bearer(request) {
  return request.headers.get('Authorization')?.match(/^Bearer ([0-9a-f]{64})$/)?.[1] || null;
}
async function participant(request, env) {
  const token = bearer(request);
  if (!token) return null;
  return env.DB.prepare('SELECT id, username FROM quiz_participants WHERE token_hash = ?').bind(await digest(token)).first();
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
        const token = bearer(request);
        if (!token) return json({error:'Nicht autorisiert.'}, 401);
        let data; try { data = await limited(request, MAX_BODY); } catch { return json({error:'Ungültige Anfrage.'}, 400); }
        if (!validUsername(data?.username) || Object.keys(data).length !== 1) return json({error:'Pseudonym ungültig.'}, 400);
        const tokenHash = await digest(token);
        const saved = await env.DB.prepare(`INSERT INTO quiz_participants(id, token_hash, username, created_at)
          SELECT ?, ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM quiz_deleted_tokens WHERE token_hash = ?)
          ON CONFLICT(token_hash) DO NOTHING`)
          .bind(crypto.randomUUID(), tokenHash, data.username, Date.now(), tokenHash).run();
        if (saved.meta?.changes) return json({username:data.username}, 201);
        const deleted = await env.DB.prepare('SELECT token_hash FROM quiz_deleted_tokens WHERE token_hash = ?').bind(tokenHash).first();
        if (deleted) return json({error:'Dieser Zugang wurde gelöscht.'}, 410);
        const existing = await participant(request, env);
        if (!existing || existing.username !== data.username) return json({error:'Zugang bereits mit anderem Pseudonym registriert.'}, 409);
        return json({username:existing.username}, 200);
      }
      if (request.method === 'GET' && path === '/participants/me') {
        const token = bearer(request);
        if (!token) return json({error:'Nicht autorisiert.'}, 401);
        const user = await participant(request, env);
        if (!user) return json({error:'Noch kein Konto registriert.'}, 404);
        return json({username:user.username});
      }
      if (request.method === 'DELETE' && path === '/participants/me') {
        const token = bearer(request);
        if (!token) return json({error:'Nicht autorisiert.'}, 401);
        const user = await participant(request, env);
        if (!user) {
          // Only a durable deletion barrier confirms a prior deletion. An unknown
          // token could still have a registration in flight and is NOT a receipt.
          const deleted = await env.DB.prepare('SELECT token_hash FROM quiz_deleted_tokens WHERE token_hash = ?').bind(await digest(token)).first();
          return deleted ? deletedReceipt() : json({error:'Nicht autorisiert.'}, 401);
        }
        // The participant DELETE trigger writes a permanent barrier within this
        // D1 transaction. It also protects old worker versions during rollout.
        // Invalidate the snapshot and advance its generation atomically.
        await env.DB.batch([
          env.DB.prepare('DELETE FROM quiz_attempts WHERE participant_id = ?').bind(user.id),
          env.DB.prepare('DELETE FROM quiz_attempt_closures WHERE participant_id = ?').bind(user.id),
          env.DB.prepare('DELETE FROM quiz_participants WHERE id = ?').bind(user.id),
          env.DB.prepare('DELETE FROM quiz_hourly_snapshot WHERE id = 1'),
          env.DB.prepare('UPDATE quiz_snapshot_generation SET generation = generation + 1 WHERE id = 1')
        ]);
        // Verify the required migration/trigger before promising a durable receipt.
        const deleted = await env.DB.prepare('SELECT token_hash FROM quiz_deleted_tokens WHERE token_hash = ?').bind(await digest(token)).first();
        if (!deleted) throw new Error('missing deletion barrier');
        return deletedReceipt();
      }
      if (request.method === 'GET' && path === '/quiz/attempt/result') {
        const user = await participant(request, env);
        if (!user) return json({error:'Nicht autorisiert.'}, 401);
        const query = new URL(request.url).searchParams;
        const dates = query.getAll('date');
        const day = dates[0];
        const parsed = typeof day === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(day) ? Date.parse(day) : NaN;
        if ([...query.keys()].some(key => key !== 'date') || dates.length !== 1 ||
            !Number.isFinite(parsed) || new Date(parsed).toISOString().slice(0, 10) !== day)
          return json({error:'Ungültiges Quizdatum.'}, 400);
        const attempt = await env.DB.prepare('SELECT correct, total, payload_hash FROM quiz_attempts WHERE participant_id = ? AND quiz_date = ?')
          .bind(user.id, day).first();
        if (!attempt) {
          const closed = await env.DB.prepare('SELECT participant_id FROM quiz_attempt_closures WHERE participant_id = ? AND quiz_date = ?').bind(user.id, day).first();
          return closed ? json({date:day, status:'closed'}, 410) : json({date:day, error:'Noch kein gewerteter Versuch.'}, 404);
        }
        if (!Number.isInteger(attempt.correct) || !Number.isInteger(attempt.total) ||
            attempt.total !== QUIZ_SIZE || attempt.correct < 0 || attempt.correct > attempt.total)
          throw new Error('invalid stored attempt');
        if (attempt.payload_hash !== null && !/^[0-9a-f]{64}$/.test(attempt.payload_hash))
          throw new Error('invalid stored hash');
        return json({date:day, correct:attempt.correct, total:attempt.total, payloadHash:attempt.payload_hash});
      }
      if (request.method === 'POST' && path === '/quiz/attempt/close') {
        const user = await participant(request, env);
        if (!user) return json({error:'Nicht autorisiert.'}, 401);
        let data; try { data = await limited(request, MAX_BODY); } catch { return json({error:'Ungültige Anfrage.'}, 400); }
        const day = data?.date;
        const parsed = typeof day === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(day) ? Date.parse(day) : NaN;
        if (!data || Object.keys(data).join(',') !== 'date' || !Number.isFinite(parsed) ||
            new Date(parsed).toISOString().slice(0,10) !== day || day > today())
          return json({error:'Ungültiges Quizdatum.'}, 400);
        // One conditional INSERT arbitrates against the conditional attempt INSERT.
        // Once closed, even a previously delayed submission cannot be recorded.
        await env.DB.prepare(`INSERT INTO quiz_attempt_closures(participant_id, quiz_date, closed_at)
          SELECT ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM quiz_attempts WHERE participant_id = ? AND quiz_date = ?)
          ON CONFLICT(participant_id, quiz_date) DO NOTHING`).bind(user.id, day, Date.now(), user.id, day).run();
        const attempt = await env.DB.prepare('SELECT correct, total, payload_hash FROM quiz_attempts WHERE participant_id = ? AND quiz_date = ?')
          .bind(user.id, day).first();
        if (attempt) {
          if (!Number.isInteger(attempt.correct) || attempt.total !== QUIZ_SIZE || attempt.correct < 0 || attempt.correct > QUIZ_SIZE ||
              (attempt.payload_hash !== null && !/^[0-9a-f]{64}$/.test(attempt.payload_hash))) throw new Error('invalid attempt');
          return json({date:day, status:'recorded', correct:attempt.correct, total:QUIZ_SIZE, payloadHash:attempt.payload_hash});
        }
        const closed = await env.DB.prepare('SELECT participant_id FROM quiz_attempt_closures WHERE participant_id = ? AND quiz_date = ?').bind(user.id, day).first();
        if (!closed) throw new Error('closure not confirmed');
        return json({date:day, status:'closed'});
      }
      if (request.method === 'GET' && path === '/quiz/today') {
        const day = today(), quiz = await loadQuiz(env, day);
        if (!quiz) return json({error:'Heute ist noch kein Quiz veröffentlicht.'}, 404);
        return json({date:day, fingerprint:quiz.fingerprint, questions:quiz.questions});
      }
      if (request.method === 'POST' && path === '/quiz/attempt') {
        const user = await participant(request, env);
        if (!user) return json({error:'Nicht autorisiert.'}, 401);
        let data, bodyHash;
        try { ({value:data, bodyHash} = await limited(request, MAX_BODY, true)); }
        catch { return json({error:'Ungültige Anfrage.'}, 400); }
        const day = today();
        if (!data || data.date !== day || typeof data.fingerprint !== 'string' || Object.keys(data).sort().join(',') !== 'answers,date,fingerprint')
          return json({error:'Nur das aktuelle Tagesquiz kann gewertet werden.'}, 400);
        const quiz = await loadQuiz(env, day);
        if (!quiz) return json({error:'Heute ist noch kein Quiz veröffentlicht.'}, 404);
        if (quiz.fingerprint !== data.fingerprint) return json({error:'Quiz wurde geändert. Bitte neu laden.'}, 409);
        let score; try { score = scoreAnswers(quiz.questions, data.answers); } catch { return json({error:'Antworten unvollständig oder ungültig.'}, 400); }
        const saved = await env.DB.prepare(`INSERT INTO quiz_attempts(participant_id, quiz_date, correct, total, submitted_at, payload_hash)
          SELECT ?, ?, ?, ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM quiz_attempt_closures WHERE participant_id = ? AND quiz_date = ?)
          ON CONFLICT(participant_id, quiz_date) DO NOTHING`)
          .bind(user.id, day, score, quiz.questions.length, Date.now(), bodyHash, user.id, day).run();
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
