-- Operator-only exact count; may scan many rows. Do not schedule as a health check.
SELECT count(*) AS exhausted FROM inbox INDEXED BY inbox_attempts
WHERE source=?1 AND state='pending' AND attempts>=?2 AND lease_until<=unixepoch();
