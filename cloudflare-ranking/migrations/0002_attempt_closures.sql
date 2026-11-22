-- Apply once to an existing ranking database before deploying the new Worker.
CREATE TABLE IF NOT EXISTS quiz_attempt_closures (
  participant_id TEXT NOT NULL REFERENCES quiz_participants(id),
  quiz_date TEXT NOT NULL,
  closed_at INTEGER NOT NULL,
  PRIMARY KEY(participant_id, quiz_date)
);

-- Also guard writes from an older Worker during rollout or in-flight requests.
CREATE TRIGGER IF NOT EXISTS quiz_attempt_reject_closed BEFORE INSERT ON quiz_attempts
WHEN EXISTS (SELECT 1 FROM quiz_attempt_closures WHERE participant_id = NEW.participant_id AND quiz_date = NEW.quiz_date)
BEGIN SELECT RAISE(ABORT, 'quiz day closed'); END;
CREATE TRIGGER IF NOT EXISTS quiz_closure_reject_attempt BEFORE INSERT ON quiz_attempt_closures
WHEN EXISTS (SELECT 1 FROM quiz_attempts WHERE participant_id = NEW.participant_id AND quiz_date = NEW.quiz_date)
BEGIN SELECT RAISE(ABORT, 'quiz attempt already recorded'); END;
