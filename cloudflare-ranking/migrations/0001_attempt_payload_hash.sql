-- Only for an existing prototype database created before payload_hash was added.
-- Apply once, BEFORE deploying the new worker. Do not apply to a fresh schema.sql database.
-- Existing attempts cannot be matched to exact request bytes, so remain NULL.
ALTER TABLE quiz_attempts ADD COLUMN payload_hash TEXT
  CHECK(payload_hash IS NULL OR (length(payload_hash) = 64 AND payload_hash NOT GLOB '*[^0-9a-f]*'));
