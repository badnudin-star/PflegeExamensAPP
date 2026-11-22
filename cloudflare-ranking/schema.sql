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
  -- Null is reserved for legacy records whose request bytes cannot be recovered.
  payload_hash TEXT CHECK(payload_hash IS NULL OR (length(payload_hash) = 64 AND payload_hash NOT GLOB '*[^0-9a-f]*')),
  PRIMARY KEY(participant_id, quiz_date)
);
CREATE INDEX IF NOT EXISTS idx_quiz_attempts_participant ON quiz_attempts(participant_id);

-- A closed day permanently rejects a delayed or subsequent submission for that account.
CREATE TABLE IF NOT EXISTS quiz_attempt_closures (
  participant_id TEXT NOT NULL REFERENCES quiz_participants(id),
  quiz_date TEXT NOT NULL,
  closed_at INTEGER NOT NULL,
  PRIMARY KEY(participant_id, quiz_date)
);

CREATE TABLE IF NOT EXISTS quiz_hourly_snapshot (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  payload_json TEXT NOT NULL,
  generated_at INTEGER NOT NULL
);

-- Enforce mutual exclusion even for an older Worker still handling a request.
CREATE TRIGGER IF NOT EXISTS quiz_attempt_reject_closed BEFORE INSERT ON quiz_attempts
WHEN EXISTS (SELECT 1 FROM quiz_attempt_closures WHERE participant_id = NEW.participant_id AND quiz_date = NEW.quiz_date)
BEGIN SELECT RAISE(ABORT, 'quiz day closed'); END;
CREATE TRIGGER IF NOT EXISTS quiz_closure_reject_attempt BEFORE INSERT ON quiz_attempt_closures
WHEN EXISTS (SELECT 1 FROM quiz_attempts WHERE participant_id = NEW.participant_id AND quiz_date = NEW.quiz_date)
BEGIN SELECT RAISE(ABORT, 'quiz attempt already recorded'); END;

-- Retained pseudonymous deletion barrier: prevents a delayed registration from resurrecting an account.
CREATE TABLE IF NOT EXISTS quiz_deleted_tokens (
  token_hash TEXT PRIMARY KEY,
  deleted_at INTEGER NOT NULL
);
CREATE TRIGGER IF NOT EXISTS quiz_participant_delete_barrier BEFORE DELETE ON quiz_participants
BEGIN
  INSERT INTO quiz_deleted_tokens(token_hash, deleted_at) VALUES (OLD.token_hash, CAST(strftime('%s','now') AS INTEGER) * 1000)
  ON CONFLICT(token_hash) DO NOTHING;
END;
CREATE TRIGGER IF NOT EXISTS quiz_participant_reject_deleted BEFORE INSERT ON quiz_participants
WHEN EXISTS (SELECT 1 FROM quiz_deleted_tokens WHERE token_hash = NEW.token_hash)
BEGIN SELECT RAISE(ABORT, 'deleted token'); END;

-- Prevent a snapshot that raced with deletion from being published afterwards.
CREATE TABLE IF NOT EXISTS quiz_snapshot_generation (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  generation INTEGER NOT NULL CHECK (generation >= 0)
);
INSERT INTO quiz_snapshot_generation(id, generation) VALUES (1, 0) ON CONFLICT(id) DO NOTHING;
