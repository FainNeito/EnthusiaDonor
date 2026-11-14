# Shared quota and hosted acceptance preparation — 2026-11-13

## Read-only account snapshot

At approximately 2026-11-13 19:36 America/New_York (2026-11-14 00:36 UTC),
the account dashboard showed the UTC daily period beginning November 14. D1
showed **314 / 5,000,000 rows read**, **49 / 100,000 rows written**, five of ten
databases, and **2.85 MB / 5 GB** total storage. Workers & Pages showed
**214 / 100,000 requests today**. The UI states both daily counters reset at
midnight UTC. These figures include other account workloads and are not an inbox
reservation. The day was only approximately 36 minutes old; the snapshot is not
a full-day baseline, a peak, or a forecast. Five database entries were visible;
no dedicated donor inbox was provisioned by this task. No usage export, database
query, configuration change or resource creation was performed.

The arithmetic remaining at that instant was 4,999,686 daily reads, 99,951
writes and 99,786 Worker requests, before other workloads continue. A whole-day
illustration for one idle consumer at a five-second minimum, plus the proposed
five-minute external monitor and 30-minute consumer status refresh, is about
323,472 D1 reads and 17,616 Worker requests. These are local-test planning
estimates, **not** additional observed usage or a prediction. They omit every
donation, retry, backlog maintenance write, other application, startup/restart
and fault scenario; the monitor and inbox are currently inactive. D1 physical
storage and Workers CPU are separate budgets. See [quota review](quota-review.md).

## Decisions before any hosted test

1. Identify an owner, an isolated test Worker and fresh dedicated D1 database,
   an approved test hostname/route and a teardown owner. Reserve an account slot;
   confirm the test is not bound to an existing database, real Tebex endpoint,
   live inbox consumer, production secrets, chat, Discord or authoritative
   outbox. This is a proposal: no resource exists from this task.
2. Record at least seven complete UTC days of account-wide reads, writes and
   Worker requests, including busy days, plus storage and per-invocation CPU
   limits. Record the daily maximum, expected growth, existing commitments,
   observed data gaps and quota-reset timezone. A single early-day dashboard
   snapshot cannot establish a peak. Confirm actual limits at test time.
3. Before scheduling, agree on a test-specific read/write/request/storage budget,
   shared-account reserve and stop thresholds with the account owner. Require
   `baseline peak + measured test/projection + reserve < limit` for **each**
   quota, and do not fill missing values with zero. Keep sufficient headroom for
   unrelated services and error/retry bursts. No numerical reserve is implied by
   this draft. If telemetry or billing granularity cannot distinguish test usage,
   classify the result inconclusive rather than subtracting unrelated traffic.
4. Prepare a separately approved lifecycle plan covering resource creation,
   permissions, test secrets, bounded synthetic traffic, rollback and removal.
   Hosted tests consume shared quota and create cloud resources; this document
   authorizes none of those actions. No production restart or deployment is part
   of this acceptance plan.

## Proposed isolated acceptance sequence

Use only synthetic Tebex-shaped bodies, fake transaction IDs and test-only secrets.
Do not forward a synthetic event into the real processor/outbox or register a
real webhook. Keep the Worker/D1/monitor versions paired: v5 status reports
`exhausted_present` (0 or 1), and incompatible monitor responses are UNKNOWN.
Use a controlled independent test client, not a production plugin or scheduler.

1. Capture pre-test account and test-resource metrics with UTC timestamps and
   identify the counter reset; wait for telemetry to settle. Install the complete
   fresh v5 schema and verify all mandatory triggers, indexes and zeroed global
   and per-source accounting. No migration from populated v1–v4 is supplied.
2. Exercise a single valid signed synthetic event, duplicate and invalid
   signature; verify durable admission before success, no mutation on rejection,
   and duplicate behavior. Check row and byte capacity boundaries and concurrent
   admissions in a bounded test fixture. Record request statuses and per-operation
   D1 rows read/written and Worker CPU as reported by the provider.
3. Verify claim lease expiry and stale ACK rejection, successful ACK clearing
   body/signature, duplicate suppression, and no cross-source access. Exercise
   an active final lease and expired exhausted event; confirm preservation,
   bounded quarantine, explicit generation-checked requeue and continued
   processing of healthy events. Test both exhaustion-presence values and monitor
   UNKNOWN for stale, malformed or mismatched status. Exact exhausted counts are
   operator-only and may scan; do not schedule them as a health check.
4. With separately agreed small quotas, test isolated completed, leased and
   exhausted/mixed synthetic backlogs and idle polling. Measure incremental
   reads, writes, requests, CPU and latency per operation and per day; compare
   those results with local fixtures and the agreed budget. Do not assume a local
   `rows_read` or `rows_written` equals hosted billable usage. Capture limit
   failures and retries, not just successful averages. Stop before any agreed
   threshold is exceeded, on unexpected production binding, telemetry gaps,
   unbounded retries, or another service approaching its reserve.
5. Check the global counters against actual rows/bytes and per-source counters
   against state counts in the **isolated** database after admission, duplicate,
   ACK, quarantine, requeue, delete and rollback. Verify no synthetic data or
   credentials entered logs, monitor output or external alert destinations.
   Perform backup/restore and duplicate/gap reconciliation only under a separate
   reviewed restore plan: restoring can resurrect previously processed events.
6. Stop the test client; verify no active test pollers or schedules remain.
   Record final metrics, failures, residual usage, test copies and responsible
   owner. Dispose of isolated resources and credentials only under the approved
   cleanup plan; preserve evidence without real payloads or secrets. Do not
   silently remove unrelated data or claim deletion erased provider history.

## Acceptance record (blank; not a pass)

- Account-wide full-day baseline and peak, source/time range: **unmeasured**.
- Agreed reserve and stop thresholds for reads/writes/requests/storage/CPU: **unassigned**.
- Isolated resource identity, owner, test window and cleanup: **unassigned**.
- Hosted per-operation and daily projections, error rates and CPU: **unmeasured**.
- Counter reconciliation, restore/gap analysis and alert receipt: **unperformed**.
- Independent monitor provider, responders and destination: **unassigned**.
- Result: **NOT ACCEPTED**. No Free-quota fit, hosted safety, RPO/RTO or production
  readiness claim follows from a local test or this point-in-time snapshot.
