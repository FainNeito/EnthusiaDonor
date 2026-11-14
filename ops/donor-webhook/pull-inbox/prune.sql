-- Bind: source, tombstone retention seconds. Retention starts at ACK, not arrival.
-- Never delete pending or quarantined rows. Historical backups are separate.
DELETE FROM inbox WHERE source=?1 AND id IN (
    SELECT id FROM inbox WHERE source=?1 AND state='done'
      AND completed_at <= unixepoch()-?2
    ORDER BY completed_at,id LIMIT 100
);
