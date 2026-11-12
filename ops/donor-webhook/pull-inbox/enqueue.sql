-- Invoke only AFTER authenticating the complete raw body and checking admission.
-- Bind: source, SHA-256(raw body), raw bytes, signature, max rows, max payload bytes.
-- Capacity check and insertion share one atomic write statement. Count ALL
-- states/sources in this dedicated database, including quarantine and tombstones.
-- Duplicates bypass capacity without changing their payload/state/lease/age.
INSERT INTO inbox(source,id,body,signature)
SELECT ?1,?2,?3,?4
WHERE EXISTS(SELECT 1 FROM inbox WHERE source=?1 AND id=?2)
   OR ((SELECT count(*) FROM inbox) < ?5
       AND (SELECT coalesce(sum(length(body)),0) FROM inbox) + length(?3) <= ?6)
ON CONFLICT(source,id) DO UPDATE SET id=excluded.id
RETURNING id;
