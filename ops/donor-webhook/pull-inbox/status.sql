-- Bind: source, max attempts. Indexed counts preserve exact status; no payloads or IDs.
SELECT unixepoch() AS observed_at, u.rows, u.payload_bytes,
       coalesce(s.pending,0) AS pending, coalesce(s.quarantined,0) AS quarantined,
       coalesce(s.completed,0) AS completed,
       (SELECT count(*) FROM inbox INDEXED BY inbox_attempts
        WHERE source=?1 AND state='pending' AND attempts>=?2 AND lease_until<=unixepoch()) AS exhausted,
       max(0,unixepoch()-min(
         coalesce((SELECT min(created_at) FROM inbox INDEXED BY inbox_source_age WHERE source=?1 AND state='pending'),unixepoch()),
         coalesce((SELECT min(created_at) FROM inbox INDEXED BY inbox_source_age WHERE source=?1 AND state='quarantined'),unixepoch())
       )) AS oldest_unprocessed_seconds
FROM inbox_usage u LEFT JOIN inbox_source_usage s ON s.source=?1 WHERE u.singleton=1 AND (s.source IS NOT NULL OR NOT EXISTS(SELECT 1 FROM inbox WHERE source=?1));
