-- Cloudflare D1 (SQLite Free Tier) Schema für das stündliche PflegeExamen-Ranking
CREATE TABLE IF NOT EXISTS scores (
  user_id TEXT PRIMARY KEY,
  username TEXT NOT NULL,
  total_xp INTEGER NOT NULL DEFAULT 0,
  streak_days INTEGER NOT NULL DEFAULT 0,
  rank_title TEXT NOT NULL DEFAULT '1. Ausbildungsjahr',
  updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_scores_rank
  ON scores(total_xp DESC, streak_days DESC, updated_at ASC);

CREATE TABLE IF NOT EXISTS hourly_snapshots (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  payload_json TEXT NOT NULL,
  generated_at INTEGER NOT NULL,
  next_update_at INTEGER NOT NULL
);
