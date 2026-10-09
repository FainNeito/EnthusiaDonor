# Donation announcement readiness — 2026-10-09

## Scope

Prepare canonical source, CI, schema and configuration for real Tebex chat and
Discord announcements. Do not restart, reload or activate either server. Do not
send announcements or replay purchases during preparation.

## Confirmed incident evidence

- SMP startup loaded EnthusiaDonors 1.1.0-test.15 at 17:36:39 and enabled it at
  17:36:57. Successful official reads occurred at 17:47:06 and 17:57:05.
- Active backend config contains the Tebex key, counting policy and
  America/Chicago leaderboard timezone, but no network or notifications sections.
  NotificationSettings.load defaults missing notifications.enabled to false.
- At 17:59:32 Tebex dispatched an Avid grant and the old broadcast command for
  la1x. LuckPerms recorded the grant. Command dispatch does not prove chat receipt.
- Proxy plugins contains relay test.13. Its relay.properties has enabled=false
  and blank JDBC/user/password/signing configuration.
- Bloom lists eight databases out of ten slots, with no dedicated donor database.

## Requirements

- When preparing notification delivery, the operator shall preserve the existing
  Tebex key, counting exclusions, timezone and privacy settings.
- When backend and proxy configuration are prepared, both shall agree on database,
  stable store source and signing key, with distinct backend/proxy identities.
- When provisioning schema, the operator shall preserve existing records and
  shall not seed payment observations, receipts or the baseline manually.
- When notifications first initialize, the plugin shall suppress historical
  purchases. This incident's purchase will not automatically replay.
- When configuration is staged without activation, notifications and relay shall
  remain disabled; the backend shall retain standalone mode until cutover.
- Before further production uploads or activation, the source shall be reviewed
  and merged, and both artifacts shall come from the clean merged commit.

## Preparation sequence

### Current preparation update

The dedicated `s109538_enthusiadonors` database and generated login now exist,
with Connections From restricted to the shared backend/proxy allocation address.
An authenticated workspace probe was rejected with SQLState 28000/code 1698
because the workstation address differs. Backend/proxy egress and TLS session
verification remain pending. No address restriction was widened.

The private backend/proxy pair is encrypted with Windows DPAPI outside Git.
It shares the database, source and signing key; notifications/relay stay disabled.
Existing smp-chatter webhook metadata was checked again without sending a message.
Schema has not been applied and live plugin files have not changed.

Test.16 preserves canonical main's legacy runtime by default and packages its
exporters and command adapter. The new adapter requires `runtime.mode: donor-network`
in the prepared backend overlay. A config.yml with no runtime mode keeps legacy
behavior, so test.15-to-test.16 replacement requires that explicit setting.
`network.initialize-schema: false` avoids publisher DDL after provisioning;
missing settings preserve the previous automatic initialization behavior.

1. Obtain approval for the dedicated database/generated login. Inspect host TLS
   support and backend/proxy reachability. Do not disable certificate validation.
2. Provision schema using ops/donor-schema.sql against that dedicated database.
   No payment data is seeded. Prefer separate publisher/producer/proxy accounts
   with the documented table privileges when host administration supports them.
   Do not reuse accounts belonging to unrelated plugins.
3. Prepare private credentials and matching settings outside Git. Preserve live
   main config; set planned Tebex poll interval to one minute at later cutover.
4. Review the source PR and exact-head Paper/Velocity CI results. Merge requires
   user authorization. Build and record clean merged artifacts before staging.
5. Stage disabled configuration/artifacts at their respective servers only after
   release gates pass. Keep active settings and servers unchanged in this task.
6. Later approved activation: enable publisher mode, real notifications and relay,
   complete baseline sync, inspect diagnostics and verify a subsequent real payment
   across two backends and smp-chatter. Coordinate removal of the old Tebex
   broadcast command only during cutover to avoid duplicate announcements.

## Evidence boundaries

Existing automated regressions cover SQLite transaction/deduplication and HTTP
failure behavior. They do not establish MariaDB dialect, TLS, production delivery,
or player acceptance. Project-local EARS/state helpers are absent. Schema/CI
preparation changes deployment support only, so no new gameplay red/green claim
applies. All remaining runtime acceptance stays open until later activation.
