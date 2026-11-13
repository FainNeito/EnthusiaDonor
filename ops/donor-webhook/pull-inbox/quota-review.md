# Inbox query and polling review — 2026-11-12

The Free account's 5 million rows read/day is shared with other applications;
there is no reserved donor budget. `quota-measure.mjs` creates only synthetic,
local Miniflare D1 databases and prints `meta.rows_read`/`rows_written` per SQL
statement. It performs operations in the listed order; quarantine mutates rows.
It is a local simulator measurement, not a hosted invoice or guarantee.

## 2026-11-13 local D1 results (schema v3)

| 10,000-row fixture | status reads | claim reads | quarantine reads / writes | prune reads | full admission reads |
| --- | ---: | ---: | ---: | ---: | ---: |
| completed, unexpired | 10,000 | 2 | 3 / 0 | 2 | 3 |
| pending, exhausted | 10,000 | 3 | 600 / 400 | 2 | 3 |
| pending, leased | 10,000 | 2 | 3 / 0 | 2 | 3 |
| 9,999 exhausted, one leased | 10,000 | 4 | 600 / 400 | 2 | 3 |
| 9,999 leased, one exhausted | 10,000 | 10,002 | 6 / 4 | 2 | 3 |

The previous all-exhausted measurements were 10,002 claim reads and 20,500
quarantine reads / 300 writes; capacity-full admission read 10,002. The revised
queries improve those cases at the cost of additional index writes. The last row
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

This does **not** establish quota compliance: 3.36M/day in status scans alone
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
