-- Apply to an existing ranking database before deploying a Worker that uses deletion barriers.
-- The trigger also protects deletions executed by an older Worker during rollout.
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
