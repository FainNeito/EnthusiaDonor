# EnthusiaDonor Tasks

## Real purchase incident — 2026-10-09

- [x] Create approved dedicated database; keep restricted source address.
- [x] Prepare encrypted private backend/proxy pair; recheck smp-chatter metadata.
- [x] Review findings: add opt-in no-DDL publisher setup and preserve the default
  legacy runtime/exporters with an explicit donor-network choice (test.16).
- [ ] Confirm backend/proxy SQL access and TLS; workspace access was rejected.
- [ ] Provision schema from reviewed merged source and complete later activation.

- [x] Identify live configuration gap: real notifications default off; proxy
  relay disabled/unconfigured. Backend test.15 loaded; Tebex entitlement grant ran.
- [x] Prepare schema and Paper/Velocity CI coverage on Java 21/25 for review.
- [ ] Provision dedicated database/login after approval; validate TLS/reachability.
- [ ] Prepare matching private backend/proxy configuration outside Git.
- [ ] Deliver reviewed source PR and inspect exact-head CI findings.
- [ ] After authorized merge, clean-build and stage merged artifacts/configuration.
- [ ] Later authorized activation and real two-backend/smp-chatter acceptance.
- No restart/reload/message is authorized in this task. Preparation contract:
  [notification-readiness.md](notification-readiness.md).

## Delivery workflow — adopted 2026-10-04

- Follow [delivery-workflow.md](delivery-workflow.md): current canonical main,
  isolated review branch, SPEAR, exact-head PR checks, merged clean builds before
  production delivery. The original checkout is preserved.
- Current review baseline: test.15, not a canonical release. Source review, PR,
  proxy CI coverage, runtime compatibility and live acceptance remain open.
- Fresh transfer verification: backend `clean verify` passed 200 tests; Velocity
  `clean verify` passed. Neither result proves active server or Discord delivery.

## Current Testing Baseline
- Real payment announcements — test.15: authenticated Tebex polls feed a shared transactional observation/outbox ledger; signed Velocity chat and a separate real Discord webhook are opt-in. User selected smp-chatter. Contract and activation gates: [payment-announcements.md](payment-announcements.md). Stage only; no restart or notification sent during development.
- Glorious minimum — test.14: configurable `glorious.minimum-monthly-donation` in config.yml, default `"30.00"`. Monthly #1 must meet the inclusive monthly aggregate minimum. Test month-close/advance paths use the setting; official Tebex finalization and permission awards remain pending.
- Latest relay pair: `EnthusiaDonors-1.1.0-test.13.jar` (Paper) and `EnthusiaDonors-VelocityRelay-1.1.0-test.13.jar` (Velocity). 161 backend tests passed, including 12 relay tests; both artifacts compiled and packaging interoperability passed. Setup: [proxy-relay.md](proxy-relay.md). Live acceptance pending; relay disabled by default.
- Previous network-data baseline: `EnthusiaDonors-1.1.0-test.12.jar`; 149 tests passed. Setup and evidence: [network-data.md](network-data.md). No production cutover or PR performed.
- Last accepted head-fix JAR: `EnthusiaDonors-1.1.0-test.11.jar` (name-first donor skin lookup and operator diagnostics; user confirmed the head skin fix on 2026-10-01; broader acceptance remains pending).
- Previous test baseline: `EnthusiaDonors-1.1.0-test.6.jar`.
- Local build verification: 139 automated tests passed for test.11; the displayed `xenokuri` name resolved a texture from this workspace with a mismatched payment UUID. The test.6 MessageRaw payload was checked for the `ALL` target and preserved rich components.
- Network test broadcasts use the opt-in database relay in test.13; when it is disabled, the existing BungeeCord-compatible `MessageRaw` path remains. Explicitly confirm the network audience. Sandbox databases remain local to each backend.
- Remaining: live official Tebex versus dashboard comparison, PlaceholderAPI/hologram and multi-backend Paper/proxy test pass for network message visibility, donation/gift variants, reload behavior, profile/history persistence, webhook behavior, permissions, and restart recovery.

## Network-Wide Donor Platform
Status: **First shared snapshot slice implemented locally in test.12; MariaDB/two-backend acceptance pending**

Goal: Donation messages, monthly top donor, and donor data/profiles must be consistent and viewable from every server on the network. The current MessageRaw test broadcast shares chat only; sandbox donor data remains backend-local. Test.12 adds optional shared official public totals, ranks and display settings; histories, monthly awards and durable announcements remain planned.

### Immediate correction — official public donor data
- [x] Replace sandbox-funded public all-time/monthly placeholders and menus with official Tebex payment totals, using the established filters, exclusions, timezone, and refund policy (local implementation).
- [x] Keep `/edonors test` simulation data in the sandbox only; never let a test purchase change a public donor rank or profile (local implementation and tests).
- [x] Preserve the last valid official snapshot on refresh failure, identify missing configuration and stale data, and reject incomplete pagination (local implementation and tests).
- [ ] Compare displayed rankings with Tebex on a Paper test server before any PR or production replacement. The test.8 JAR is built locally.
- [x] Repair donor GUI head skin rendering. User confirmed the fix on 2026-10-01 after test.11; broader GUI/profile acceptance remains part of the final test pass.
- [ ] Confirm test.10 no longer produces Paper/authlib session-server timeout stack traces when opening donor menus, rendering broadcast previews, or resolving an offline test name. A first-time offline texture may remain neutral while Mojang is unreachable.
- Diagnostic available if head rendering recurs: `/edonors headcheck <official donor name>`. Running this command is not required to close the user-confirmed skin fix.

### Spec — authority and public contract
- [ ] Define one authoritative Tebex payment ledger and shared database, player UUID identity, package filters, refund/chargeback handling, gift buyer/recipient attribution, public profile fields, and privacy rules.
- [ ] Fix one network timezone and month boundary. Specify how a monthly winner is finalized once, how permanent Glorious history is retained, and how cancellation differs from paid-access expiry.
- [x] Define shared read states (`FOUND`, `NOT_FOUND`, `FAILED`) and stale-display behavior. A failed shared read must not erase cached donor totals or turn a profile into zero.

### Prove — cross-server and failure tests
- [ ] Add tests for duplicate/out-of-order Tebex events, reconciliation, refunds, gifts, month rollover, one-time awards, and announcement retries.
- [ ] Prove that two Paper backends show the same `/donors` profile, history, leaderboard, and PlaceholderAPI values before and after transfer, reload, and restart.
- [ ] Prove database outage/recovery retains the last valid display snapshot and that a proxy restart cannot duplicate a donation announcement.

### Engine — shared accounting and projections
- [ ] Add a single Tebex ingestion owner using verified webhooks and paginated payment reconciliation. Store unique payment/event IDs and apply state changes idempotently in a shared database.
- [ ] Build shared donor profile, all-time and monthly leaderboard snapshots from committed payments. Finalize the monthly winner in one transaction with a uniqueness guard.
- [x] Expose versioned, public-safe read projections to backend plugins. Keep Tebex secrets and payment writes out of display backends; keep sandbox and production storage separate.

### Architecture — network delivery and display
- [x] Add asynchronous publisher/reader snapshot integration for existing donor GUI, commands and PlaceholderAPI caches (local implementation; MariaDB/Paper acceptance pending).
- [ ] Store announcement events durably with payment changes. Add a Velocity relay that sends each event across the network with delivery/deduplication tracking and no originating-player requirement.
- [x] Build the Velocity test relay and backend local outbox with signed events, expiry, immutable IDs and durable claims/receipts (test.13, local implementation). Explicit test notices are connected; real payment-transaction outbox remains open above.
- [x] Prove local relay outage/restart, concurrent claim exclusion, signature rejection, conflict handling, expiry and partial-send/completion uncertainty (SQLite fixtures). MariaDB/Velocity/player-visible proof remains open.
- [ ] Define authenticated backend/proxy communication, least-privilege database access, cache invalidation or revision polling, and clear diagnostics for stale data.

### Refine — migration and acceptance
- [ ] Back up the existing production donor data, import and reconcile history, and compare old versus shared totals and monthly winners before any cutover.
- [ ] Test with at least two backends plus Velocity: simultaneous views, player transfer, proxy/backend restart, refunds, month rollover, outage recovery, and network-wide chat receipt.
- [ ] Verify the supported Paper/Leaf version and hologram/PlaceholderAPI client display. Approve production migration separately after the shared test environment passes.

## Completed Bulk Update — test.5

### Make the entire donor broadcast configurable
Status: **Implemented and built**

Move the remaining hard-coded broadcast presentation into config so the message can be restyled without rebuilding the plugin.

Planned configurable fields:
- Header text, including `$$ Donation Broadcast`
- Test/build marker text
- Top divider
- Bottom divider
- The 8 text rows aligned beside the player face
- Store CTA wording
- Store URL display text
- Purchase, subscription, gift, renewal, and Glorious variants

Requirements:
- Preserve the NotBounties-style 8x8 player-face rendering.
- Keep placeholders such as `<buyer>`, `<recipient>`, `<rank>`, `<month>`, and `<store>`.
- Continue supporting MiniMessage formatting.
- Keep this build isolated from live Tebex, production donor data, R2, and LuckPerms writes.

## Network-Wide Test Broadcasts — test.6
Status: **Implemented; live proxy verification pending**

- Sends the rendered component rows using the proxy's BungeeCord-compatible `MessageRaw` message for target `ALL`.
- Requires `broadcast.allow-network-audience: true` and `/edonors test audience network confirm`.
- The network audience resets on player leave; `/edonors test audience self` returns to private previews.
- Velocity must allow its BungeeCord plugin message channel. The sending backend needs at least one connected player to carry the message.
- Other backends do not need the plugin for receipt. Install test.6 on each test backend where staff need to originate test events.
- Test donor state is still local to the backend that records it; this change shares chat only, not sandbox profiles/leaderboards.

### Rank colors in donor broadcasts
Status: **Implemented and built**

Use the following exact rank styling whenever the `<rank>` placeholder is rendered in donor broadcasts:

- **Devotee**
  - Source formatting: `&#0007FF&l[&#070CFF&lD&#0E12FF&le&#1417FF&lv&#1B1DFF&lo&#2222FF&lt&#2927FF&le&#2F2DFF&le&#3632FF&l]`
  - Preserve the per-character blue gradient and bold styling.
  - Normalize the legacy hex/ampersand syntax internally so it renders safely through the broadcast component pipeline.

- **Avid**
  - `<#00C4FF><bold>[<#17CCFF><bold>A<#2ED3FF><bold>v<#44DBFF><bold>i<#5BE2FF><bold>d<#72EAFF><bold>]</bold><reset>`

- **Glorious**
  - `<b><gradient:#FF110A:#C70000>[Glorious]</gradient></b>`

Requirements:
- These styles apply to donor messages and any configurable broadcast templates using `<rank>`.
- Do not flatten the gradients to a single color.
- Do not include the testing marker as part of the rank text.
- Keep the rank strings configurable in the bulk-update config rather than hard-coding them.
