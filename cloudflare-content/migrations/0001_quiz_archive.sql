-- Apply once to an existing content database before deploying the archive endpoints.
CREATE TABLE IF NOT EXISTS quiz_archive (
  date TEXT PRIMARY KEY,
  question_count INTEGER NOT NULL CHECK (question_count > 0),
  payload TEXT NOT NULL
);
