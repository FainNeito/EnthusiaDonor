-- OPERATOR-ONLY offline preparation. Not imported by the Worker or consumer API.
-- After diagnosing failure, bind source, exact digest ID, observed quarantine_count.
-- Compare-and-set prevents a repeated recovery request from resetting a later cycle.
-- Original body/signature/created_at remain unchanged; downstream dedup still applies.
UPDATE inbox SET state='pending', attempts=0, quarantined_at=NULL,
                 lease_token=NULL, lease_until=0
WHERE source=?1 AND id=?2 AND state='quarantined' AND quarantine_count=?3
RETURNING id;
