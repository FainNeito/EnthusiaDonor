-- Invoke only after authoritative processing/outbox commit (or durable duplicate).
-- Keep a payload-free tombstone; neither stale leases nor another store can ACK.
-- Bind: source, body digest ID, current lease token.
UPDATE inbox SET state='done', body=NULL, signature=NULL,
                 lease_token=NULL, lease_until=0, completed_at=unixepoch()
WHERE source=?1 AND id=?2 AND state='pending'
  AND lease_token=?3 AND lease_until>unixepoch()
RETURNING id;
