-- Apply only during the reviewed journal rollout, BEFORE the new worker is
-- deployed. Existing/older workers then cannot delete accounts, until their
-- deletion path is replaced. Backfill must run after this guard is in place.
-- The permit is created and removed in the same transaction as deletion, only
-- after the separate journal has been written and read back. An administrator
-- with unrestricted SQL privileges can bypass this guard; restrict credentials.
CREATE TABLE quiz_journal_delete_permits (
  token_hash TEXT PRIMARY KEY
);
CREATE TRIGGER quiz_participant_requires_journal_permit BEFORE DELETE ON quiz_participants
WHEN NOT EXISTS (SELECT 1 FROM quiz_journal_delete_permits WHERE token_hash = OLD.token_hash)
BEGIN SELECT RAISE(ABORT, 'journal permit missing'); END;
