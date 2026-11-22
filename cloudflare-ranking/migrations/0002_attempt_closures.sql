-- Apply once to an existing ranking database before deploying the new Worker.
CREATE TABLE IF NOT EXISTS quiz_attempt_closures (
  participant_id TEXT NOT NULL REFERENCES quiz_participants(id),
  quiz_date TEXT NOT NULL,
  closed_at INTEGER NOT NULL,
  PRIMARY KEY(participant_id, quiz_date)
);
