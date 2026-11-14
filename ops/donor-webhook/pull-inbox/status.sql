-- Bind: source, max attempts. Indexed counters and exhaustion presence; no payloads or IDs.
SELECT unixepoch() AS observed_at, u.rows, u.payload_bytes,
       coalesce(s.pending,0) AS pending, coalesce(s.quarantined,0) AS quarantined,
       coalesce(s.completed,0) AS completed,
       EXISTS(WITH RECURSIVE levels(n) AS (SELECT ?2 UNION ALL SELECT n+1 FROM levels WHERE n<20)
        SELECT 1 FROM levels WHERE EXISTS (SELECT 1 FROM inbox INDEXED BY inbox_attempts
          WHERE source=?1 AND state='pending' AND attempts=levels.n AND lease_until<=unixepoch())) AS exhausted_present,
       max(0,unixepoch()-min(
         coalesce((SELECT min(created_at) FROM inbox INDEXED BY inbox_source_age WHERE source=?1 AND state='pending'),unixepoch()),
         coalesce((SELECT min(created_at) FROM inbox INDEXED BY inbox_source_age WHERE source=?1 AND state='quarantined'),unixepoch())
       )) AS oldest_unprocessed_seconds
FROM inbox_usage u LEFT JOIN inbox_source_usage s ON s.source=?1 WHERE u.singleton=1 AND (s.source IS NOT NULL OR NOT EXISTS(SELECT 1 FROM inbox WHERE source=?1));
