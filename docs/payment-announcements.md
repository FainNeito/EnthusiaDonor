# Real Tebex payment announcements

## Spec

- Read completed payments from the authenticated official Tebex Plugin API, after a complete successful page set. Do not infer purchases from leaderboard changes, player commands or sandbox events.
- Opt in with `notifications.enabled`. Use one `network.mode: publisher` source, a shared MariaDB notification ledger/outbox, and the configured signed Velocity relay. Readers never ingest or dispatch real payments.
- The first successful sync establishes a persistent source baseline and suppresses historical purchases. Afterwards, each newly completed qualifying payment created at/after that baseline creates at most one chat and one Discord job. Pending payments can become completed; replay, restart, overlapping refreshes and publisher changes cannot repeat them.
- Preserve counting exclusions/package filters; never announce unpaid, free, manual, refunded, chargeback, future, invalid-identity or pre-baseline payments. Do not guess gift payer, package entitlement or renewal information absent from the Plugin API.
- Persist payment observation and immutable channel payloads in one transaction before sending. Publish signed chat jobs idempotently; do not extend expired events or use an unsaved fallback broadcast.
- Use a separate real Discord webhook configuration targeting the user's smp-chatter channel. Suppress mentions. Claim before HTTP dispatch; retry only definitive 429 rejections with a bounded delay. Preserve timeout/crash/5xx outcomes as uncertain and never blindly resend.
- Real announcements contain no simulated-event marker. Keep the existing eight-row face style and public profile click actions. A failed skin lookup must not prevent the payment announcement.
- Stage the resulting Paper JAR on SMP in Chapter 2 staging and the Velocity JAR in a proxy staging directory. No restart, reload, test-channel or production-channel message, plugin activation, PR or live payment mutation is authorized by this staging request.

## Architecture and activation

Test.16 requires `runtime.mode: donor-network`. Its default legacy runtime retains
the original command/export path. Configure `network.initialize-schema: false`
alongside the existing relay/notification schema flags after provisioning. A
test.15 config lacking runtime mode must receive the explicit opt-in at cutover.

Authenticated complete Tebex poll -> shared observations + immutable outbox -> signed relay publication -> Velocity network chat; a separate durable Discord job uses the existing smp-chatter webhook. Delivery follows the configured official refresh interval (not an instant webhook push). No incoming public HTTP port is needed. Existing sandbox previews remain isolated.

Before later activation, configure a shared source and database, publisher/relay identities and signing key, schema privileges/provisioning, Discord webhook and optional store URL. Backend and proxy must agree on relay source/key/database. Keep all historical observation/outbox tables across upgrades. Inspect uncertain delivery receipts before any manual resend. Official Glorious finalization and permission grants remain separate work.

The publisher needs SELECT/INSERT/UPDATE on `enthusiadonors_notify_sources`, `enthusiadonors_notify_seen`, and `enthusiadonors_notify_jobs`. Provision their schema once using `notifications.initialize-schema: true` with schema privileges, then disable schema creation. The configured `network.source-id` is the stable store namespace: keep it unchanged across publisher/key changes, and never share it between different Tebex stores. The relay continues using the legacy `enthusiadonors_test_events` and `enthusiadonors_test_receipts` table names for protocol compatibility; real payment IDs use a separate `payment-` event prefix.

Chat jobs expire 15 minutes after preparation; Discord pending jobs expire after 24 hours. Neither expiry nor an uncertain claim creates a fresh event automatically. `/edonors notifications` shows ingestion state and job counts; `/donorrelay receipt <event-id>` shows proxy delivery state. `CLAIMED` after a crash must be treated as uncertain. Keep disabled channels disabled on both active and standby publishers.

## Staged deployment checklist

1. Backend: `plugins/chapter 2 staging/EnthusiaDonors-1.1.0-test.15.jar`. Proxy: `plugins/chapter 2 staging/EnthusiaDonors-VelocityRelay-1.1.0-test.15.jar`. These are inactive staging paths. Do not leave duplicate versions in either active plugins directory during a later cutover.
2. Merge the private `EnthusiaDonors-notifications-test15.staged.yml` sections into existing backend config at the approved activation; preserve the existing Tebex key/counting/timezone configuration. Its existing webhook was verified read-only as **Enthusia Purchases**, targeting **smp-chatter**. It remains disabled in the staged file.
3. Set the backend `network.mode: publisher`, shared MariaDB database/source/credentials and matching relay source/database/backend/signing key. Set matching proxy properties in `plugins/enthusiadonors-relay/relay.properties` using the existing relay setup guide. Snapshot readers must keep real notifications disabled.
4. Enable the notifications and relay only during the approved cutover. Existing official refresh interval is 10 minutes; set `tebex.refresh-interval-minutes: 1` if one-minute polling is desired. This reads complete official page sets and does not provide instant webhook delivery.
5. Later startup must first complete a baseline sync without historical messages. Verify publisher notification state, proxy relay state and a subsequent real payment on two connected backends plus smp-chatter. No restart or live notification was performed during staging.

## Prove

Regressions: first-sync suppression, duplicate/restart/concurrent observation, pending-to-complete, old/future/reversed/free/manual/excluded payments, atomic rollback, durable outbox and independent channel claims, immutable relay payload after publication failure, 429/5xx/timeout outcomes, mention suppression and no test marker. SQLite fixtures prove state behavior; MariaDB and live Paper/Velocity/Discord acceptance require later activation.

Tebex API reference: https://docs.tebex.io/plugin/endpoints/payments
Discord webhook reference: https://docs.discord.com/developers/resources/webhook#execute-webhook
