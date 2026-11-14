# Inbox query and polling review — 2026-11-12

The Free account's 5 million rows read/day is shared with other applications;
there is no reserved donor budget. `quota-measure.mjs` creates only synthetic,
local Miniflare D1 databases and prints `meta.rows_read`/`rows_written` per SQL
statement. It performs operations in the listed order; quarantine mutates rows.
It is a local simulator measurement, not a hosted invoice or guarantee.

## Exhaustion signal and write tuning (schema v5)

Routine status now exposes `exhausted_present` as numeric 0 or 1: it means at
least one pending event has reached the configured maximum attempts and its
lease has expired. It is **not a count**. `inspect-exhausted-count.sql` provides
an exact operator-only count on demand and may scan the full backlog. Attention
and CRITICAL classification still activate when any such event exists; a live
final-attempt lease does not count until expiry. The field was renamed deliberately:
old and new monitor/Worker versions must be paired; the strict new monitor returns
UNKNOWN on an old response. Schema v5 enforces integer attempts 0–20, matching the
validated policy range, and probes indexed attempt/lease ranges to find presence.
No migration from populated v1–v4 is provided; verify data and install all
mandatory triggers before any separately authorized migration or restore.

| Local D1 scenario | Prior v4 | v5 |
| --- | ---: | ---: |
| Status, 10,000 exhausted rows | 10,004 reads | 6 reads |
| Status, 10,000 completed or active leased rows | 5 reads | 37 reads |
| Quarantine 100 exhausted rows | 800 reads / 700 writes | 800 reads / 400 writes |
| New admission | 7 reads / 9 writes | 7 reads / 7 writes |
| Duplicate admission | 6 reads / 5 writes | 6 reads / 3 writes |
| Claim one | 15 reads / 3 writes | 15 reads / 2 writes |
| ACK one | 7 reads / 8 writes | 7 reads / 6 writes |
| Prune one completed row | 7 reads / 3 writes | 7 reads / 3 writes |

Measurements are synthetic Miniflare D1, default max attempts 5, not hosted
billing; the figures for individual writes come from `quota-write-measure.mjs`
with baseline ref `37d4684efb78dc9e3730427d5e972d1ba25799cf`. The removed
redundant index and partial pending/completed indexes reduce writes at the cost
of more index probes for an empty exhaustion check. Presence checks are bounded
by the schema/policy attempt range; the exact operator count remains potentially
expensive. Shared 100k/day write and 5M/day read allowances also cover other
applications and all traffic, retries, maintenance and monitoring. With one idle
consumer at the minimum five-second cadence, 18 local reads per claim/maintenance
and 336 status checks at 37 reads is about 323,472 reads/day, excluding all event
traffic and other workloads. This is an illustration, not proof of quota fit.
Account-specific usage, peak backlog, request/CPU quotas, trigger/index write
costs and hosted behavior remain gates. No monitor, schema or production service
was activated.

Pre-change presence test failed because the prior API provided only an exact count;
the prior v4 measured 10,004 reads for the large exhausted fixture. The v5 local
suite covers active/expired final leases, limits changed up and down, max attempts,
invalid attempts, exact operator count, monitor schema rejection, and accounting.
45 Node tests and nine SQLite tests pass locally; see PR for exact-head CI.

## Earlier mixed-backlog baseline (schema v4)

| 10,000-row local fixture | status reads | claim reads | quarantine reads / writes |
| --- | ---: | ---: | ---: |
| completed | 5 | 13 | 3 / 0 |
| exhausted | 10,004 | 13 | 800 / 700 |
| leased | 5 | 13 | 3 / 0 |
| 9,999 exhausted, one leased | 10,003 | 13 | 800 / 700 |
| 9,999 leased, one exhausted | 5 | 13 | 8 / 7 |

Counts come from synthetic local Miniflare D1 with default max attempts 5;
they are not hosted billing. Claim probes each attempt count below the configured
limit (maximum 20) with an equality and lease-time index range. It preserves
attempt-priority ordering but may delay higher-attempt events under sustained
lower-attempt traffic. The aggregate status retains exact source counts and
age/attention semantics. Transactionally maintained per-source counters remove
full scans for ordinary completed/leased data; oldest pending/quarantine use an
age index. Exact expired-exhausted count can still read all exhausted rows and
also scan active final-attempt leases. The status query has not become universally
constant cost. Quarantine now writes more due to the additional counters/index.

For an illustrative one-consumer idle day at the 5-second minimum, with default
attempts, no exhaustion and the local measurements above, 17,280 polls times
roughly 18 reads (claim 13 plus two maintenance queries 3+2) is 311,040 reads;
288 independent and 48 consumer status queries at five reads each add 1,680.
This is not a quota reservation or worst-case forecast: monitor is inactive,
5–7 seconds is jittered, billing may differ, and other workloads, event traffic,
retries, exhausted rows, writes and request/CPU quotas are excluded. The shared
Free limit and health-signal latency still require measured headroom and hosted
validation before activation.

Schema v4 is fresh-only. Its usage and per-source counters depend on all mandatory
triggers; missing counters fail closed in tested cases, but missing triggers or
manual counter changes can silently corrupt counts. Validate schema/trigger
presence and reconcile both global and per-source counters against inbox contents
under a separately authorized ingestion/consumer boundary, before any restore or
migration. No migration for populated v1–v3 databases is supplied. Counter and
index writes count against the shared write budget; no production setup occurred.

Two targeted pre-change local tests failed on 10,002 mixed-claim and 10,000
completed-status reads. The updated suite exercises exact counts through source
and state changes, rollback, missing metadata and mixed backlog. See PR CI.

## Earlier baseline: schema v3

| 10,000-row fixture | status reads | claim reads | quarantine reads / writes | prune reads | full admission reads |
| --- | ---: | ---: | ---: | ---: | ---: |
| completed, unexpired | 10,000 | 2 | 3 / 0 | 2 | 3 |
| pending, exhausted | 10,000 | 3 | 600 / 400 | 2 | 3 |
| pending, leased | 10,000 | 2 | 3 / 0 | 2 | 3 |
| 9,999 exhausted, one leased | 10,000 | 4 | 600 / 400 | 2 | 3 |
| 9,999 leased, one exhausted | 10,000 | 10,002 | 6 / 4 | 2 | 3 |

The previous all-exhausted measurements were 10,002 claim reads and 20,500
quarantine reads / 300 writes; capacity-full admission read 10,002. The v3
queries improved those cases at the cost of additional index writes. The last row
shows an adversarial mix still causes a full claim scan. The Worker runs bounded
quarantine before claim, but larger mixes can still leave exhausted rows after a
pass; no general constant-time or quota guarantee is claimed. Claim now orders by
attempt count, lease time and creation time, rather than global creation order.
A sustained stream of lower-attempt work could delay higher-attempt work.

Admission now consults a database-wide singleton, updated by mandatory SQLite
triggers in the same transaction as row insertion, payload changes and deletion.
Duplicates do not increase it; ACK subtracts payload bytes and pruning subtracts
rows. Missing singleton blocks admission and relevant writes. A missing trigger,
manual counter edits, incomplete schema setup or inconsistent restore can make
metadata wrong: before hosting verify all triggers and reconcile the singleton
against `count(*)` and `sum(length(body))` with ingestion/consumption stopped in
a separately authorized maintenance window. Do not silently repair a mismatch.
Schema v3 is for a fresh database; no populated-v1/v2 migration is supplied.
Back up and reconcile before any independently designed migration or restore.
Trigger/index writes also consume the account's shared write quota.

At the row cap, independent status scans every five minutes are approximately
2.88M reads/day. The consumer's former idle scan every 5–7 seconds could add
123–173M/day. Its successful status cache now lasts at most 30 minutes, reducing
that component to about 0.48M/day at the cap for one continuously idle consumer.
The first idle poll checks immediately. Cached attention is preserved; a failed
or malformed refresh produces RETRY, and is retried. Attention changes can take
up to 30 minutes to appear locally. The proposed independent monitor remains
inactive; its five-minute signal is not available until separately installed.

The earlier v3 baseline did **not** establish quota compliance: 3.36M/day in status scans alone
leaves under 1.64M for all other account traffic, and some mixed backlog patterns can still scan thousands per claim. Idle claim plus maintenance run every
5–7 seconds, and batches with many exhausted rows can consume quota quickly.
Additional consumers, traffic, retries, and non-donor databases share the limit.
Even the request count and write budgets must be checked. No load or cost claim
is extrapolated to production billing. Before activation, bound mixed-backlog cost and verify accounting and write costs, select a budget
with headroom, and validate representative
hosted D1 metrics and alert latency. Do not activate this design solely on the
cache change. No quota, plan, polling deployment, or monitor was changed live.

Test evidence: the cache-cadence assertion failed against the prior implementation
(two status calls rather than one); the updated consumer passes it, including
retained attention and failed refresh. Full-suite and CI results are recorded in
the draft PR. This is a partial tuning step, not a guarantee of Free-tier fit.

Two new assertions failed on the original v2 implementation (missing usage table
and 10,002 exhausted-claim reads); 40 local Node tests and nine SQLite tests now
pass. Synthetic local D1 metrics do not certify hosted quota usage.
