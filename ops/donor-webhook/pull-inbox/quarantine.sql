-- Bind: source, max attempts. Preserve payload; never revoke an active lease.
-- At most 100 transitions per maintenance pass. Claim also excludes exhausted rows.
UPDATE inbox INDEXED BY sqlite_autoindex_inbox_1 SET state='quarantined', quarantined_at=unixepoch(),
                 quarantine_count=quarantine_count+1, lease_token=NULL, lease_until=0
WHERE source=?1 AND id IN (
    SELECT id FROM inbox INDEXED BY inbox_attempts WHERE source=?1 AND state='pending'
      AND attempts>=?2 AND lease_until<=unixepoch()
    ORDER BY attempts,lease_until,created_at,id LIMIT 100
);
