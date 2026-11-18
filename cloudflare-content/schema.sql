-- A single atomically replaced published snapshot; no draft/source documents or secrets here.
CREATE TABLE IF NOT EXISTS content_snapshot (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  revision INTEGER NOT NULL,
  payload TEXT NOT NULL
);
