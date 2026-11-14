-- OPERATOR-ONLY metadata inspection; never returns customer payloads/signatures.
-- Bind source. Inspect the oldest 100 quarantined rows before any explicit recovery.
SELECT id,created_at,quarantined_at,attempts,quarantine_count,length(body) AS payload_bytes
FROM inbox WHERE source=?1 AND state='quarantined'
ORDER BY quarantined_at,id LIMIT 100;
