-- Bind: source, max attempts. Aggregates only; no IDs, signatures or bodies.
SELECT count(*) AS rows, coalesce(sum(length(body)),0) AS payload_bytes,
       coalesce(sum(source=?1 AND state='pending'),0) AS pending,
       coalesce(sum(source=?1 AND state='quarantined'),0) AS quarantined,
       coalesce(sum(source=?1 AND state='done'),0) AS completed,
       coalesce(sum(source=?1 AND state='pending' AND attempts>=?2 AND lease_until<=unixepoch()),0) AS exhausted,
       coalesce(max(CASE WHEN source=?1 AND state!='done' THEN max(0,unixepoch()-created_at) END),0) AS oldest_unprocessed_seconds
FROM inbox;
