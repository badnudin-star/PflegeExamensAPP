/**
 * PflegeExamen Lokal - Kostenloser Cloudflare Worker + D1 Ranking-Service (0 EUR)
 * - POST /score       -> Spielstand speichern (mit Basis-Plausibilitaetscheck) + akt. Stunden-Snapshot
 * - GET  /leaderboard -> Vorberechnetes stuendliches Ranking aus D1 ausliefern
 * - GET  /health      -> Erreichbarkeitstest fuer die App
 * - Cron (0 * * * *)  -> Erzeugt jede volle Stunde den Snapshot und exportiert optional leaderboard.json nach GitHub
 */

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
};

const HOUR_MS = 60 * 60 * 1000;

function jsonResponse(data, status = 200, cacheSeconds = 0) {
  const cacheControl =
    status === 200 && cacheSeconds > 0
      ? `public, max-age=${cacheSeconds}`
      : "no-store";
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": cacheControl,
      ...CORS_HEADERS,
    },
  });
}

function sanitizeUsername(raw) {
  const cleaned = String(raw || "")
    .replace(/[^\p{L}\p{N}_\- .]/gu, "")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 24);
  return cleaned;
}

function nextTopOfHour(nowMs) {
  return Math.ceil((nowMs + 1000) / HOUR_MS) * HOUR_MS;
}

async function buildAndSaveHourlySnapshot(env, nowMs = Date.now()) {
  const topRows = await env.DB.prepare(
    `SELECT user_id, username, total_xp, streak_days, rank_title, updated_at
     FROM scores
     ORDER BY total_xp DESC, streak_days DESC, updated_at ASC
     LIMIT 100`
  ).all();

  const countRow = await env.DB.prepare(
    `SELECT COUNT(*) AS cnt FROM scores`
  ).first();

  const entries = (topRows.results || []).map((row, idx) => ({
    rank: idx + 1,
    userId: row.user_id,
    username: row.username,
    totalXp: Number(row.total_xp || 0),
    streakDays: Number(row.streak_days || 0),
    rankTitle: row.rank_title || "1. Ausbildungsjahr",
    updatedAtMillis: Number(row.updated_at || nowMs),
    isCurrentUser: false,
  }));

  const nextUpdate = nextTopOfHour(nowMs);
  const snapshot = {
    generatedAtMillis: nowMs,
    nextUpdateAtMillis: nextUpdate,
    totalParticipants: Number(countRow?.cnt || entries.length),
    entries,
    sourceLabel: "Stündliches Community-Ranking",
  };

  const payloadJson = JSON.stringify(snapshot);
  await env.DB.prepare(
    `INSERT INTO hourly_snapshots (id, payload_json, generated_at, next_update_at)
     VALUES (1, ?, ?, ?)
     ON CONFLICT(id) DO UPDATE SET
       payload_json = excluded.payload_json,
       generated_at = excluded.generated_at,
       next_update_at = excluded.next_update_at`
  )
    .bind(payloadJson, nowMs, nextUpdate)
    .run();

  return { snapshot, payloadJson };
}

async function getCurrentHourlySnapshot(env, rebuildIfEmpty = false) {
  const existing = await env.DB.prepare(
    `SELECT payload_json, next_update_at FROM hourly_snapshots WHERE id = 1`
  ).first();

  const nowMs = Date.now();
  if (
    existing &&
    existing.payload_json &&
    Number(existing.next_update_at || 0) > nowMs
  ) {
    try {
      const parsed = JSON.parse(existing.payload_json);
      if (!rebuildIfEmpty || Number(parsed.totalParticipants || 0) > 0) {
        return parsed;
      }
    } catch {
      // Beschädigtes Snapshot-JSON wird unten neu aufgebaut.
    }
  }
  const built = await buildAndSaveHourlySnapshot(env, nowMs);
  return built.snapshot;
}

async function maybePushSnapshotToGitHub(env, payloadJson) {
  if (!env.GITHUB_TOKEN || !env.GITHUB_REPO) return;
  const branch = env.GITHUB_BRANCH || "main";
  const filePath = env.GITHUB_FILE_PATH || "docs/leaderboard.json";
  const apiUrl = `https://api.github.com/repos/${env.GITHUB_REPO}/contents/${filePath}`;

  const headers = {
    Authorization: `Bearer ${env.GITHUB_TOKEN}`,
    Accept: "application/vnd.github+json",
    "User-Agent": "PflegeExamen-Ranking-Worker",
    "Content-Type": "application/json",
  };

  let existingSha = undefined;
  const getRes = await fetch(`${apiUrl}?ref=${encodeURIComponent(branch)}`, { headers });
  if (getRes.ok) {
    const meta = await getRes.json();
    existingSha = meta.sha;
  }

  const bytes = new TextEncoder().encode(payloadJson);
  let binary = "";
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  const contentBase64 = btoa(binary);

  const putBody = {
    message: `chore(ranking): hourly leaderboard snapshot ${new Date().toISOString()}`,
    content: contentBase64,
    branch,
    ...(existingSha ? { sha: existingSha } : {}),
  };

  await fetch(apiUrl, {
    method: "PUT",
    headers,
    body: JSON.stringify(putBody),
  });
}

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: CORS_HEADERS });
    }

    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "") || "/";

    if (request.method === "GET" && path === "/health") {
      return jsonResponse({
        ok: true,
        service: "pflege-examen-ranking",
        updateIntervalMinutes: 60,
        freeTier: true,
      });
    }

    if (request.method === "GET" && (path === "/leaderboard" || path === "/")) {
      const snapshot = await getCurrentHourlySnapshot(env);
      return jsonResponse(snapshot, 200, 60);
    }

    if (request.method === "POST" && path === "/score") {
      let body;
      try {
        body = await request.json();
      } catch {
        return jsonResponse({ error: "Ungueltiges JSON" }, 400);
      }

      const userId = String(body.userId || "").trim().slice(0, 64);
      const username = sanitizeUsername(body.username);
      if (!userId || username.length < 2) {
        return jsonResponse({ error: "userId und gueltiger username erforderlich" }, 400);
      }

      const rawXp = Number(body.totalXp);
      const requestedXp = Number.isFinite(rawXp)
        ? Math.max(0, Math.min(500000, Math.floor(rawXp)))
        : 0;
      const rawStreak = Number(body.streakDays);
      const streakDays = Number.isFinite(rawStreak)
        ? Math.max(0, Math.min(3650, Math.floor(rawStreak)))
        : 0;
      const rankTitle = String(body.rankTitle || "1. Ausbildungsjahr").trim().slice(0, 48);
      const nowMs = Date.now();

      // Basis-Schutz gegen unplausible XP-Spruenge pro Stunde
      const existing = await env.DB.prepare(
        `SELECT total_xp, updated_at FROM scores WHERE user_id = ?`
      )
        .bind(userId)
        .first();

      let acceptedXp = requestedXp;
      if (existing) {
        const prevXp = Number(existing.total_xp || 0);
        const hoursElapsed = Math.max(1, (nowMs - Number(existing.updated_at || nowMs)) / HOUR_MS);
        const maxAllowed = prevXp + Math.ceil(hoursElapsed * 5000);
        if (acceptedXp > maxAllowed) {
          acceptedXp = maxAllowed;
        }
      }

      await env.DB.prepare(
        `INSERT INTO scores (user_id, username, total_xp, streak_days, rank_title, updated_at)
         VALUES (?, ?, ?, ?, ?, ?)
         ON CONFLICT(user_id) DO UPDATE SET
           username = excluded.username,
           total_xp = excluded.total_xp,
           streak_days = excluded.streak_days,
           rank_title = excluded.rank_title,
           updated_at = excluded.updated_at`
      )
        .bind(userId, username, acceptedXp, streakDays, rankTitle, nowMs)
        .run();

      const snapshot = await getCurrentHourlySnapshot(env, true);
      return jsonResponse(snapshot);
    }

    return jsonResponse({ error: "Not found" }, 404);
  },

  async scheduled(_event, env, ctx) {
    ctx.waitUntil(
      (async () => {
        const { payloadJson } = await buildAndSaveHourlySnapshot(env, Date.now());
        await maybePushSnapshotToGitHub(env, payloadJson);
      })()
    );
  },
};
