-- Neue, getrennte Tabellen: vorhandene alte Lernpunkte werden nicht übernommen.
CREATE TABLE IF NOT EXISTS quiz_participants (
  id TEXT PRIMARY KEY,
  token_hash TEXT NOT NULL UNIQUE,
  username TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS quiz_attempts (
  participant_id TEXT NOT NULL REFERENCES quiz_participants(id),
  quiz_date TEXT NOT NULL,
  correct INTEGER NOT NULL CHECK(correct >= 0),
  total INTEGER NOT NULL CHECK(total = 50 AND correct <= total),
  submitted_at INTEGER NOT NULL,
  PRIMARY KEY(participant_id, quiz_date)
);
CREATE INDEX IF NOT EXISTS idx_quiz_attempts_participant ON quiz_attempts(participant_id);

CREATE TABLE IF NOT EXISTS quiz_hourly_snapshot (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  payload_json TEXT NOT NULL,
  generated_at INTEGER NOT NULL
);
