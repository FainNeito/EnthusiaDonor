-- Probe each allowed attempt count (policy limits this to 20) using an indexed lease range.
-- Bind: source, fresh random token, bounded lease seconds, max attempts.
UPDATE inbox SET lease_token=?2, lease_until=unixepoch()+?3, attempts=attempts+1
WHERE source=?1 AND id=(
  WITH RECURSIVE levels(n) AS (SELECT 0 UNION ALL SELECT n+1 FROM levels WHERE n+1 < ?4)
  SELECT (SELECT id FROM inbox INDEXED BY inbox_attempts
          WHERE source=?1 AND state='pending' AND attempts=levels.n AND lease_until<=unixepoch()
          ORDER BY lease_until,created_at,id LIMIT 1) AS candidate
  FROM levels WHERE candidate IS NOT NULL ORDER BY levels.n LIMIT 1
)
RETURNING id,body,signature,lease_token,lease_until,attempts;
