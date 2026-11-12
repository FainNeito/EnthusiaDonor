# Inbox query and polling review — 2026-11-12

The Free account's 5 million rows read/day is shared with other applications;
there is no reserved donor budget. `quota-measure.mjs` creates only synthetic,
local Miniflare D1 databases and prints `meta.rows_read`/`rows_written` per SQL
statement. It performs operations in the listed order; quarantine mutates rows.
It is a local simulator measurement, not a hosted invoice or guarantee.

| 10,000-row fixture | status reads | claim reads | quarantine reads / writes | prune reads | full admission reads |
| --- | ---: | ---: | ---: | ---: | ---: |
| completed, unexpired | 10,000 | 3 | 4 / 0 | 2 | 10,002 |
| pending, exhausted | 10,000 | 10,002 | 20,500 / 300 | 2 | 10,002 |
| pending, leased | 10,000 | 3 | 4 / 0 | 2 | 10,002 |

At the row cap, independent status scans every five minutes are approximately
2.88M reads/day. The consumer's former idle scan every 5–7 seconds could add
123–173M/day. Its successful status cache now lasts at most 30 minutes, reducing
that component to about 0.48M/day at the cap for one continuously idle consumer.
The first idle poll checks immediately. Cached attention is preserved; a failed
or malformed refresh produces RETRY, and is retried. Attention changes can take
up to 30 minutes to appear locally. The proposed independent monitor remains
inactive; its five-minute signal is not available until separately installed.

This does **not** establish quota compliance: 3.36M/day in status scans alone
leaves under 1.64M for all other account traffic, and admission and exhausted
backlog can scan thousands per operation. Idle claim plus maintenance run every
5–7 seconds, and batches with many exhausted rows can consume quota quickly.
Additional consumers, traffic, retries, and non-donor databases share the limit.
Even the request count and write budgets must be checked. No load or cost claim
is extrapolated to production billing. Before activation, redesign or bound the
admission/backlog costs, select a budget with headroom, and validate representative
hosted D1 metrics and alert latency. Do not activate this design solely on the
cache change. No quota, plan, polling deployment, or monitor was changed live.

Test evidence: the cache-cadence assertion failed against the prior implementation
(two status calls rather than one); the updated consumer passes it, including
retained attention and failed refresh. Full-suite and CI results are recorded in
the draft PR. This is a partial tuning step, not a guarantee of Free-tier fit.
