-- One atomic statement, database clock, one event per bounded outbound pull.
-- Bind: source, fresh random token, bounded lease seconds, max attempts.
UPDATE inbox SET lease_token=?2, lease_until=unixepoch()+?3, attempts=attempts+1
WHERE source=?1 AND id=CASE WHEN EXISTS(
    SELECT 1 FROM inbox INDEXED BY inbox_pending
    WHERE source=?1 AND state='pending' AND lease_until<=unixepoch()
) THEN (
    SELECT id FROM inbox INDEXED BY inbox_attempts
    WHERE source=?1 AND state='pending' AND attempts < ?4 AND lease_until<=unixepoch()
    ORDER BY attempts,lease_until,created_at,id LIMIT 1
) ELSE NULL END
RETURNING id,body,signature,lease_token,lease_until,attempts;
