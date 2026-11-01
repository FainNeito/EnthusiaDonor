# Live Glorious monthly award

## Spec and acceptance boundaries

The current live donor-network runtime displays official Tebex donor totals, but
its month-end awards and all LuckPerms writes remain disconnected. The requested
feature grants the permanent `glorious` membership to the single #1 qualifying
donor when a calendar month closes, provided that month's official counted
donations meet the configurable `glorious.minimum-monthly-donation` (default
30.00 USD, inclusive). The configured leaderboard timezone defines the boundary.

- When the official complete payment reconciliation is successful after a month
  closes, the award shall be calculated from that closed month's eligible payment
  records, not from the new month's display or the isolated test ledger.
- When data is incomplete, stale, identity is ambiguous, a currency cannot be
  compared safely with USD, the database or LuckPerms is unavailable, or the
  publisher lease is lost, the system shall hold the award and retain evidence.
- When a payment dated in the closed month is still pending, the month shall
  remain held until that payment reaches a terminal status, even if no currently
  completed payment meets the minimum. This conservative hold applies before
  finalization because a later settlement can change the winner.
- When the leader has less than the minimum or there are no qualifying payments,
  no group shall be granted. If qualifying totals tie, use the same deterministic
  winner ordering specified for the sandbox. Do not distribute multiple awards.
- When more than one server, poll or restart processes a month, a shared durable
  per-source/month decision shall prevent conflicting winners and preserve the
  intent and outcome of a rank grant. A failed or uncertain LuckPerms save must
  never be reported as a confirmed award or blindly retried.
- When an authorized reviewer independently verifies an uncertain award already
  has its permanent direct Glorious membership, the system may record the
  reviewer and evidence digest and mark that interrupted or uncertain decision awarded.
  This confirmation shall never grant, remove, reset, or retry a membership.
  An absent rank remains on hold; no automatic retry follows an interrupted grant.
- When a gifted purchase's payer cannot be verified, hold the award for review;
  the user selected this policy rather than counting an unverified recipient.
- A payer review shall record the source, closed month, payment hash, verified
  payer UUID, reviewer UUID, evidence digest and database timestamp. A repeated
  identical review is idempotent; a conflicting review or a review after a
  monthly decision shall fail without replacing the prior record. No operator
  command or authorization boundary is implied by the storage adapter.
- When granting, add only the verified winner's permanent direct global Glorious
  membership, preserve all existing ranks, and never take away earlier awards.
  Offline and renamed players require safe UUID/customer binding; a Tebex ID or
  current name alone does not prove historical player ownership.
- Disabled by default. User chose only month ends after activation; do not
  award any month whose end preceded first activation, including after restart.
  Persist the activation boundary and never silently move it backwards. Operator
  authorization, reviewed source, separate schema/configuration and isolated
  Paper/LuckPerms tests precede any live activation.
- Once a correctly configured publisher acquires its lease, it shall attempt to
  persist the activation boundary before requesting Tebex payments. A Tebex
  outage shall not silently shift that boundary past a month end. A failed
  activation attempt shall leave awards held and must not block public refresh.

## SPEAR state

Canonical base `3c50f93486464dc8b90dd04345ee0101cdcd1254`, isolated branch.
The Paper publisher hook and reviewer commands are implemented in local source,
disabled by default and not deployed. The full coordinated offline Java 25 run
passed 246 tests, zero failures/errors/skips (Roundtable 3419/3420). All 20
compiled LuckPerms API method descriptors used by the two touched platform
classes were present in the saved SMP LuckPerms 5.5.85 runtime JAR. This does
not prove end-to-end publisher behavior, buyer identity, official payment
completeness, or production behavior. Isolated MariaDB concurrency and an earlier
synthetic Paper/LuckPerms grant/recovery probe passed; that probe predates the
activation-order change. Prove, architecture and refine are not complete.
The repository has no project-local EARS/state helpers; this document and
`docs/tasks.md` / `docs/verification.md` record the task and evidence instead.
No production rank, data, schema, configuration, server or client change is
authorized by this implementation request alone.

## Source completeness finding

The existing `TebexClient.parsePayments` silently omits records with missing
payment IDs or unparseable player UUIDs, and catches other malformed records.
Pagination completion therefore does not establish that all potentially eligible
payments are available for a permanent award. A live award path must explicitly
detect incomplete identity/amount records for the target month and HOLD; it must
not simply reuse the public display fetch. The official documented payload does
not establish a distinct gift payer in every case. Resolve attribution from a
verified authoritative source before granting a recipient as if they were the
payer. Source: https://docs.tebex.io/plugin/endpoints/payments

## Review operations (local implementation; not live)

The configured publisher exposes `/edonors glorious pending <yyyy-mm> [page]`
and `/edonors glorious review <yyyy-mm> <payment-hash> <payer-uuid>
<evidence-sha256> confirm` to players with both the root command permission and
`enthusiadonors.glorious.review`, or the console. The hash is the plugin's
SHA-256 of the Tebex Plugin API payment `id`, not the transaction string shown
on the receipt. The evidence digest identifies independently retained evidence;
the reviewer must independently verify the actual payer and their Minecraft
UUID before submitting it. The command itself cannot prove that evidence.
Unverified eligible payments hold the month, including ordinary purchases when
the available source cannot establish the buyer; this may require a review for
each payment. Console reviews use the all-zero UUID as the reviewer identity.
No raw payment or evidence is stored in the review table. A completed review
is reconsidered on the next official publisher refresh. A stale or missing
lease, missing schema, source failure or unavailable LuckPerms holds the work.

`glorious.live-enabled` defaults to false. Before activation, provision and
review the four InnoDB tables in `glorious-schema.sql`, verify backup and
permissions, and perform separate Paper/LuckPerms and MariaDB concurrency tests.
For an `UNCERTAIN` or interrupted `GRANTING` state, `/edonors glorious status <yyyy-mm>` shows the recorded
winner UUID. An authorized reviewer must independently verify that exact player
already has permanent direct global Glorious membership in durable LuckPerms
storage, retain evidence, and then use `/edonors glorious confirm <yyyy-mm>
<winner-uuid> <evidence-sha256> rank-verified`. This is a human attestation; it
does not inspect LuckPerms or change any rank. The confirmation and reviewer
are recorded atomically with the decision. If membership is absent or the state
is ambiguous, leave the decision held for investigation. No automatic retry is
performed after an uncertain permission save. Two matching scans detect some
changes but cannot guarantee a transactionally consistent Tebex snapshot.

## Database disconnect fault evidence — 2026-11-01

A disposable MariaDB 11.8.3 fixture killed the lease transaction and allowed a
new owner to take over. When the disconnect occurred before the final lease check,
the synthetic external write was prevented. When it occurred immediately after
that check, the synthetic write completed after takeover and the database
transaction failed. The row lock therefore cannot fence an in-flight LuckPerms
save after a connection loss. Treat this as an unresolved activation blocker;
the test did not use a real LuckPerms instance or live data. Fixture source:
`src/test/fixtures/MariaDbLeaseLossProbe.java`. The official Tebex example shows
package entries with numeric IDs, but does not prove every real store payment has
nonempty package metadata: https://docs.tebex.io/plugin/endpoints/payments.
