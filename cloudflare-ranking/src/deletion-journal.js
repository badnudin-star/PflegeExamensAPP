// Staged building block, not yet connected to production deletion handling.
// Only a validated hash and timestamp go into the independent database. A
// successful call means a row was read back, not that a deletion is complete.
export async function recordDeletion(journal, tokenHash, now = Date.now()) {
  if (!journal || typeof journal.prepare !== 'function' ||
      typeof tokenHash !== 'string' || !/^[0-9a-f]{64}$/.test(tokenHash) ||
      !Number.isSafeInteger(now) || now < 0) throw new Error('invalid journal input');
  const result = await journal.prepare(`INSERT INTO deletion_journal(token_hash, recorded_at)
    VALUES (?, ?) ON CONFLICT(token_hash) DO NOTHING`).bind(tokenHash, now).run();
  if (result?.success !== true) throw new Error('journal write not confirmed');
  const row = await journal.prepare('SELECT token_hash, recorded_at FROM deletion_journal WHERE token_hash = ?')
    .bind(tokenHash).first();
  if (!row || row.token_hash !== tokenHash || !Number.isSafeInteger(row.recorded_at) || row.recorded_at < 0)
    throw new Error('journal readback not confirmed');
  return row.recorded_at;
}
