# Account backup and alert-routing evidence — 2026-11-12

Read-only authenticated dashboard inspection; no database query, export, bookmark
retrieval, restore or configuration mutation. Identifiers and unrelated data are
omitted from this review.

| Dashboard evidence | Observation | What it establishes |
| --- | --- | --- |
| Workers plans | Free marked Current plan | Actual Workers account tier, not a zone's plan badge |
| D1 inventory, unfiltered | 5/10 databases; 499/5M rows read today; 0/100k written; 2.8 MB/5 GB | Point-in-time shared usage, not reserved capacity |
| Existing development database, Time Travel | Past seven days; displayed window 05 Nov 2026 02:08 through 12 Nov 2026 02:08 | Available recovery window for that sample; dashboard timezone not established |
| D1 inventory | No dedicated donor-inbox-named database listed | This task has provisioned no inbox; does not rule out tables inside unrelated databases |
| Notifications | Enabled default billing budget email alert | Billing notification only, not inbox monitoring |
| Notifications / Destinations | Empty destination onboarding, no configured destination cards displayed | No external alert destination established by this inspection |

The account plan and sampled seven-day capability are verified. Donor-specific
backup history, actual restore correctness, export inventory, physical erasure,
recovery objectives and ownership remain unverified. No donor database exists
from this preparation, so a donor backup/restore acceptance test cannot yet be
performed. Follow [backup-retention-review.md](backup-retention-review.md) before
any separately authorized hosted exercise. ACK does not erase historical copies.

## Capacity gate

At 10,000 retained rows, five-minute external aggregate scans could read about
2.88 million rows/day. Idle consumer scans every roughly 5–7 seconds could add
about 123–173 million rows/day. These are worst-case scan planning estimates,
not measured billing, but exceed the observed shared Free quota of 5 million
reads/day. Other account workloads also consume that budget. Measure and tune
status-query cadence or implementation before deployment; this review does not
certify that the default policy fits Free. No plan upgrade was purchased.

## Routing bindings

Provider, primary responder, backup responder and destination remain unassigned
in the inactive example. The existing billing alert is not reused or changed.
Pure routing decisions are prepared in [alert-routing.md](alert-routing.md);
provider persistence, independent watchdog and actual destination receipt remain
acceptance gates. No alerts, schedules, secrets or destinations were created.

A later read-only dashboard snapshot and the prepared hosted test gates are in
[hosted-acceptance.md](hosted-acceptance.md). The prior capacity numbers above
were v2 estimates; current v5 measurements and their limitations are recorded
in [quota-review.md](quota-review.md). No hosted inbox was created.
