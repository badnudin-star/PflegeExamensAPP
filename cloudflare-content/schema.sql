-- A single atomically replaced published snapshot; no draft/source documents or secrets here.
CREATE TABLE IF NOT EXISTS content_snapshot (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  revision INTEGER NOT NULL,
  payload TEXT NOT NULL
);

-- Append-only public copies of individual quiz days. Never store credentials here.
CREATE TABLE IF NOT EXISTS quiz_archive (
  date TEXT PRIMARY KEY,
  question_count INTEGER NOT NULL CHECK (question_count > 0),
  payload TEXT NOT NULL
);
