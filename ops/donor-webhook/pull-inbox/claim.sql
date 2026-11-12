-- One atomic statement, database clock, one event per bounded outbound pull.
-- Caller authentication occurs outside SQL; generate token on the server.
-- Bind: source, fresh random token, bounded lease seconds, max attempts.
UPDATE inbox SET lease_token=?2, lease_until=unixepoch()+?3,
                 attempts=attempts+1
WHERE source=?1 AND id=(
    SELECT id FROM inbox WHERE source=?1 AND state='pending'
      AND lease_until<=unixepoch()
      AND attempts < ?4
    ORDER BY created_at,id LIMIT 1
)
RETURNING id,body,signature,lease_token,lease_until,attempts;
