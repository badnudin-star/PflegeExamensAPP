-- Separate deletion journal only. This schema must never be installed in the
-- production ranking database. Hashes are pseudonymous personal data.
CREATE TABLE deletion_journal (
  token_hash TEXT PRIMARY KEY CHECK(length(token_hash) = 64 AND token_hash NOT GLOB '*[^0-9a-f]*'),
  recorded_at INTEGER NOT NULL CHECK(typeof(recorded_at) = 'integer' AND recorded_at >= 0)
);

-- Ignore duplicates before conflict handling so INSERT OR REPLACE cannot replace
-- existing rows even when SQLite recursive triggers are disabled.
CREATE TRIGGER deletion_journal_no_replace BEFORE INSERT ON deletion_journal
WHEN EXISTS (SELECT 1 FROM deletion_journal WHERE token_hash = NEW.token_hash)
BEGIN SELECT RAISE(IGNORE); END;
CREATE TRIGGER deletion_journal_no_update BEFORE UPDATE ON deletion_journal
BEGIN SELECT RAISE(ABORT, 'append only'); END;
CREATE TRIGGER deletion_journal_no_delete BEFORE DELETE ON deletion_journal
BEGIN SELECT RAISE(ABORT, 'append only'); END;
