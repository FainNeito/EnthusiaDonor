# Inbox capacity, quarantine and retention — preparation only

This is the next SPEAR slice of the [outbound design](../outbound-inbox-plan.md).
It implements local policy and tests; it does not configure a live service.

## Requirements and defaults

| Requirement | Draft default | Implementation |
| --- | --- | --- |
| Admission must not exceed the logical row/payload budget, even concurrently | 10,000 rows; 64 MiB body bytes | One conditional INSERT/UPSERT in `enqueue.sql` |
| A rejected new payment must not receive success | HTTP 503 with Retry-After: 60 | Worker checks both SQL success and one returned ID |
| Existing duplicates must succeed at capacity without resetting evidence | Preserve state, lease, attempts and timestamps | No-op conflict update returns the stored ID |
| Repeated failed delivery must stop automatic retry without losing the event | Five expired attempts | `quarantine.sql`, plus claim excludes exhausted rows |
| Active leases must remain ACK-able, including the last allowed attempt | 90 seconds, unchanged | Quarantine applies only after DB-clock lease expiry |
| Completion must promptly remove live payloads | Clear body/signature on valid ACK | Existing ACK now also records DB-clock completion time |
| Retention must never silently delete unprocessed payments | Pending/quarantine retained until explicit resolution | Prune selects completed tombstones only |
| Completed replay protection must have a defined retention window | 90 days after ACK | At most 100 tombstones removed per pass |
| An empty queue must not conceal quarantine/backlog/capacity pressure | Authenticated aggregate status | Plugin reports INBOX_ATTENTION on an idle claim |

Optional trusted Worker bindings:

| Binding | Default | Accepted range |
| --- | ---: | ---: |
| INBOX_MAX_ROWS | 10000 | 1–10000 |
| INBOX_MAX_BYTES | 67108864 | 262144–67108864 |
| INBOX_MAX_ATTEMPTS | 5 | 1–20 |
| INBOX_TOMBSTONE_DAYS | 90 | 7–365 |

Values must be integer strings/numbers in range; missing values use defaults,
invalid values fail closed. These draft bounds are not account quota or billing
approval. The database is dedicated to this inbox. Row and body-byte accounting
includes **all namespaces and states**, including quarantined payloads and done
tombstones. It measures logical retained data, not physical SQLite pages, indexes,
WAL, backups or total account storage. Quota/database failures still return 503.
Admission currently aggregates the bounded table; hosted query-cost/latency and
capacity sizing require acceptance before deployment. Do not raise caps or delete
pending work automatically to relieve pressure.

## Maintenance and retention

Authenticated payment ingestion and claim requests await a transactional D1 batch
that quarantines at most 100 exhausted expired events, then prunes at most 100
old completed tombstones. Validation handshakes, bad signatures, bad consumer
credentials and status reads do not run maintenance. Batch failure produces a
retryable failure and never permits a successful payment acknowledgement.
An optional `scheduled` handler uses the same bounded maintenance and propagates
failures. **No Cron trigger or Worker deployment is configured.** Without traffic
or a future approved Cron trigger, retention is not a wall-clock deletion SLA.
Large eligible backlogs need multiple passes.

Maintenance is scoped to the configured SOURCE even though capacity counts the
whole dedicated database. Completion age comes from `completed_at`, not arrival
time. Duplicate deliveries do not refresh completion age. Once a tombstone is
pruned, an old signed delivery can enter the inbox again: authoritative lookup
and the existing durable shared outbox remain the business deduplication layer.
Never clear or replace those ledgers as part of inbox retention or rollback.

Pending and quarantined bodies have **no automatic expiry**. A stale or malformed
event can therefore retain customer data until an operator resolves it. This is
an explicit preservation policy, not a claim of finite raw-data retention. A
separate approved disposition/export/deletion policy is required if that is not
acceptable. ACK clears current-row data, not historical backup copies. D1 Time
Travel is always on and retains database history; actual account/plan behavior
and any external exports must be included in the later data-retention review.
[Cloudflare backup documentation](https://developers.cloudflare.com/d1/reference/time-travel/).

## Quarantine and recovery

An attempt is a granted lease, not proof of a handler error. Crashes, missing
eligibility, lookup outages, lost responses and slow processing can all exhaust
attempts. Quarantine records its timestamp and a monotonically increasing
`quarantine_count`, preserves the original bytes/signature, clears the lease, and
blocks further claims. Duplicates do not reopen it. Other healthy events continue
to be claimable. A late/stale ACK is rejected; the outbox may already have committed,
so recovery must assume at-least-once delivery.

The consumer API has no requeue, purge, body inspection or arbitrary-source route.
`inspect-quarantine.sql` is an operator-only, source-scoped, bounded metadata query
(digest ID, times, counts, byte length; no raw payload or signature). After checking
credentials, eligibility/baseline, official lookup health and durable outbox state,
an operator may explicitly select one event for `requeue.sql`. Bind the source,
digest and observed quarantine_count. Recovery resets attempts but preserves the
payload and arrival time; the expected count prevents a repeated/stale recovery
request from resetting a later quarantine cycle. Review the returned ID and do
not automatically loop over all events. These SQL statements were exercised only
against synthetic local databases; no real recovery or deletion was performed.

## Visibility and schema boundary

POST `/consumer/status` accepts only `{}` and the existing separate consumer bearer
token. It returns aggregate counts/age/usage and an `attention` flag: at least 80%
of either capacity, any quarantine/exhausted event, or unprocessed age >= 1 hour.
No bodies, signatures or IDs are returned. The plugin queries status when a claim
returns 204 and displays INBOX_ATTENTION instead of INBOX_EMPTY when needed. Failed
or malformed status responses produce INBOX_RETRY, never a false healthy state.
This is a pull-based status signal, not an external alert service or notification.
External monitoring, ownership and escalation remain deployment gates.

`schema.sql` is now v2 for fresh databases. No hosted v1 database was created in
this task, so there is no live migration. The v1 CHECK constraint does not allow
quarantine and its columns are insufficient: do not point this Worker at a v1
database or treat CREATE TABLE as a migration. Any pre-existing populated v1 inbox
would need a separate data-preserving migration and verification plan. Keep
consumer and Worker versions paired; an older Worker without `/consumer/status`
will make the new consumer report RETRY on idle claims.

## Evidence and review

- Before implementation, five policy tests failed against the previous code:
  four behavioral assertions (row budget, byte budget, quarantine, status route)
  and one missing `completed_at` schema error. Captured in `target/policy-red.log`.
- Two new Java status tests failed before integration; the prior five consumer
  tests passed. Captured in `target/policy-consumer-red.log`.
- Local Worker/workerd/D1 suite: 21 tests passed, including concurrent row and
  byte admission, duplicate/tombstone capacity, active/stale leases, quarantine
  isolation, bounded maintenance, recovery generations, payload-free status,
  retention timing/scope, configuration errors and failed maintenance.
- Existing SQLite protocol suite: 8 passed. Full local Java verification: 241
  run, 240 passed, one optional external MariaDB test skipped. CI results for the
  exact pushed head are recorded in draft PR #9.
- Self-review: preservation at capacity, leased-work races, source boundaries,
  replay after retention, backup limitations and empty-status visibility checked.
  No independent review approval or hosted acceptance is claimed.

PR #9 remains draft. No merge, server restart/reload, production upload, DNS/port
change, Worker/D1 provisioning, live maintenance/secret/registration change or
production activation is authorized or performed.
