# Official donor display test.11 verification

## Reviewed preparation — test.16

- Findings addressed: the publisher can register against externally provisioned
  schema with network.initialize-schema=false; missing configuration and the old
  NetworkSettings constructor retain automatic initialization. Legacy startup
  routing and packaged exporters/commands preserve canonical main's runtime;
  the newer adapter requires runtime.mode=donor-network. Invalid modes and
  incompatible legacy/network configuration fail before new workers start.
- SPEAR red: publisher-schema-red.log failed compilation before the new config
  accessor/initialization overload existed. Focused green: 13 tests. The first
  fixture denied SELECT statement creation too; it was corrected to deny CREATE
  operations while permitting reads. runtime-mode-red.log failed compilation
  before routing types existed; runtime-mode-green.log passed 15 focused tests.
- Final local clean verify: 205 tests, zero failures/errors/skips
  (test16-backend-build.log); proxy clean verify passed (test16-proxy-build.log).
- JAR audit: LegacyRuntime, R2UploadService, JsonExportService and DonorCommand
  are packaged in the backend again. Proxy retains its independent shaded build.
- Local unmerged Paper SHA-256: 40697BDBC8EA3AC5056F927DB28C6851AC2831963E59903C1487A6FBFCDA4341.
  Local unmerged Velocity SHA-256: D10FD636CB9B28AD01796AD9E4426B5B8A0CC146E8147AA4B326566D2F2C5BEA.
  Neither is a production artifact or staged file.
- The authenticated workspace SQL probe was denied by the source-host restriction
  (SQLState 28000, code 1698). This proves rejected workspace access only, not
  successful backend/proxy SQL connectivity or an established TLS session.
- Private configuration is DPAPI-encrypted outside Git, with matching source,
  database and signing settings. Notifications/relay remain disabled; smp-chatter
  webhook metadata verified through a read-only GET, with no message sent.
- No schema applied, production plugin file changed, merge, restart/reload,
  activation or payment replay. No project-local EARS/state helper exists.
- Exact-head GitHub checks and review records must be inspected after this update;
  earlier Java 21/25 success for f5dfee11 does not verify the test.16 head.

## Notification readiness — 2026-10-09

- Fetched canonical main again: eede9199d0ff6156ec4a6b05e46f1749e60f3eb4.
- Live read-only diagnosis and remaining gates are in notification-readiness.md.
- Backend clean verify passed 200 tests, zero failures/errors/skips
  (readiness-backend-build.log). Velocity clean verify passed
  (velocity-relay/readiness-proxy-build.log), running on local JDK 25 with
  compiler release 21. This is not a Java 21 runtime or live delivery test.
- CI now prepares both artifacts/checksums on Java 21 and 25; exact-head
  GitHub results must be inspected separately after the draft PR is opened.
- ops/donor-schema.sql matches the six runtime table definitions; the example
  configuration uses placeholders and disabled preparation flags. No schema was
  applied, credential created, production file changed, or server restarted.

## Workflow adoption — 2026-10-04

- Canonical main explicitly fetched at eede9199d0ff6156ec4a6b05e46f1749e60f3eb4.
- Accumulated source transferred to isolated `codex/donor-network-review` from
  that base; original working checkout preserved. No merge or production action.
- Backend `mvn --batch-mode --no-transfer-progress clean verify`: 200 tests,
  zero failures/errors/skips; BUILD SUCCESS (`workflow-backend-build.log`).
- Velocity `clean verify`: BUILD SUCCESS (`velocity-relay/workflow-proxy-build.log`).
- These are fresh local build results for unmerged test.15 source. No GitHub CI,
  active runtime, MariaDB or player/Discord acceptance is implied.
- Delivery gates and absent EARS/state tooling: [delivery-workflow.md](delivery-workflow.md).

## SPEAR state

- Spec: `docs/requirements.md` defines official Tebex as the sole source of public donor ranks, profiles, and placeholders. Simulated test donors stay in `/edonors test`.
- Prove: Focused tests cover official placeholder mapping, privacy, monthly rollover, counting policy, missing dates, and incomplete pagination.
- Engine: The test build reads the paginated Tebex Plugin API into a local cache under `testing/official-tebex/<key fingerprint>/donors.db`. It retains the last valid official snapshot on fetch failure. Local kill/death placeholders keep their separate stats cache.
- Architecture: The JAR excludes production R2 and LuckPerms write paths. PlaceholderAPI remains optional.
- GUI heads: Profile texture lookup is independent of broadcast skin-image download. Test.11 uses the current Mojang Java username first, then the Tebex payment UUID; it saves successful texture URLs under `testing/skins/head-textures.properties` and backs off for one minute after failures. `/edonors headcheck <name>` reports the read source. A resolved texture is written back to the open inventory slot.
- Refine: Run `mvn clean package`, inspect JAR contents, then complete the live checks below.

## Local artifact

- `C:\Users\p_ric\Downloads\EnthusiaDonors-1.1.0-test.11.jar`
- SHA-256: `1D34F7FC1EA1D6FF66577CE5388FA8C6AAC99335566271D6F99FA9B4306DBE78`
- `mvn clean package`: 139 tests passed. A direct local Mojang lookup for `xenokuri` returned a texture through the name route even with a deliberately mismatched payment UUID.
- No server installation or PR has been performed for test.11.

## Live test sequence

1. Back up the test server's current JAR and `plugins/EnthusiaDonors` data folder. Confirm `config.yml` contains the intended Tebex Plugin API key, timezone, package filters, exclusions, and refund policy. Keep the key out of logs and screenshots. Install test.11 and restart; config changes need a restart. Confirm `testing.yml` has `skins.network-enabled: true`.
2. Run `/edonors status` and `/edonors refresh`. Wait for `Official Tebex donor refresh complete` and an `OK` state. If the key is absent, pagination is incomplete, or an excluded transaction cannot be resolved, investigate the status and logs before comparing ranks. The old valid official snapshot should remain visible on fetch failure.
3. Compare `/edonors top monthly`, `/edonors top` and `/donors` monthly/all-time menus with the Tebex dashboard using the same timezone and counting filters. Check several ranked donors and totals, including refunds and excluded transactions.
4. Parse `%enthusiadonors_monthly_top_1_name%`, `%enthusiadonors_alltime_top_1_name%`, `%enthusiadonors_status%`, and kill/death placeholders through PlaceholderAPI. Confirm the donor values match official menus and the holograms after their refresh interval. Kill/death values remain local Bukkit statistics.
5. Run `/edonors test populate 10` or a simulated purchase. Confirm the `[TEST]` menu changes but the public `/donors` menus and donor placeholders do not. Recheck after a restart.
6. Check empty ranks, `privacy.show-amounts: false`, missing Tebex key, and failed refresh. Public donor values must never fall back to simulated donors.
7. Open monthly and all-time donor menus and public profiles. Check that known Java player heads show their skins after texture lookup, including an offline donor and the online viewer; navigate away and reopen to check caching. A synthetic `/edonors test` identity may retain the neutral texture. If official names load but every real head stays default, check server profile lookup/network errors and the `skins.network-enabled` setting.
8. Run `/edonors headcheck xenokuri` and several other displayed donor names after opening the menu. Confirm `texture available` and a Mojang name or payment UUID source; if neutral, record the reported status and check server HTTPS access to Mojang. During menu opens, broadcast previews, and offline test-name resolution, check for new Paper/authlib session-server timeout stack traces. Cached or online skins should remain during an outage; first-time offline heads can stay neutral until access returns.

Live Paper, PlaceholderAPI, hologram, and Tebex comparisons remain pending. This test JAR is not a production release.

## Shared snapshot slice — test.12

- Artifact: `C:\Users\p_ric\Downloads\EnthusiaDonors-1.1.0-test.12.jar`
- SHA-256: `E40510FAECBF5BF2B085B1E643D2BE1D41C56A3E6934AD4451A348D579392EFA`
- `mvn clean package`: 149 tests passed, zero failures/errors/skips. New network coverage: 10 tests. Shared projection fixtures use independent SQLite connections; MariaDB acceptance remains pending.
- Packaged JAR contains all network classes and declares MariaDB Connector/J 3.4.1 in `plugin.yml`. Source/destination hashes matched; Remote Desktop Commander confirmed the delivered file exists (544,861 bytes).
- Setup, contract and MariaDB/two-backend test sequence: [network-data.md](network-data.md).
- No server deployment, commit, push, PR or production migration performed in this slice.

## Velocity relay — test.13

- Paper artifact: `C:\Users\p_ric\Downloads\EnthusiaDonors-1.1.0-test.13.jar` (593,573 bytes).
- Paper SHA-256: `DDB64FCA5CF59FCE10A1E77A95A6D7D253C4215456FBC6D8EA31AF1B1FBEB6CC`.
- Velocity artifact: `C:\Users\p_ric\Downloads\EnthusiaDonors-VelocityRelay-1.1.0-test.13.jar` (5,508,257 bytes).
- Velocity SHA-256: `E0DCE800B8723AD75E88A944CCCE912F9B3478D7DB5CDFCFB2C066A7A0C80436`.
- SPEAR spec: [proxy-relay.md](proxy-relay.md). Red: initial `RelayTest` compilation failed before relay types existed. Green: 12 focused relay tests; the full backend suite passed 161 tests, with zero failures/errors/skips. Backend clean package passed, followed by package after the final operator-message refinement. Velocity clean package passed against API 3.4.0-SNAPSHOT.
- Relay tests cover HMAC round trips/tampering, immutable IDs, local restart and outage retry, concurrent and independent proxy claims, crash/restart suppression, no-player behavior, partial-send uncertainty, completion-write failure, malformed payload isolation, expiry, conflict isolation and rich component preservation. Persistence fixtures use SQLite; no MariaDB server was used.
- Artifact audit confirmed Paper core/backend classes, proxy metadata/entry point, relay core, packaged MariaDB driver and relocated Gson. Proxy JAR contains no Paper sandbox classes or embedded SLF4J API. Isolated classloader smoke test passed for an event signed by the packaged Paper JAR and verified by the shaded proxy JAR, including JSON equality and driver loading.
- Source/delivered hashes matched. Remote Desktop Commander verified both Downloads artifacts and sizes.
- Relay remains disabled by default. Live MariaDB/Velocity/Paper delivery, permissions, TLS, player-visible layout, no-origin-player testing, outage recovery and shutdown acceptance remain pending. Real Tebex ingestion and a payment-transaction outbox are not connected in this test slice.
- No server changes, commit, push, PR or production migration performed.

## Glorious monthly minimum — test.14

- Requirement: monthly #1 must donate at least $30.00 that month; configurable with `glorious.minimum-monthly-donation` in config.yml. Missing key defaults to 30.00; restart applies changes.
- SPEAR red: new GloriousMinimumTest failed compilation before the policy and configurable ledger entry points existed (`glorious-red.log`). Focused green: 53 tests passed before three additional attribution/winner/reversal regressions. Final clean package: 177 tests, zero failures/errors/skips (`glorious-build.log`).
- Sixteen new regressions cover the inclusive default, monthly aggregation versus lifetime, configurable higher/lower minimums, clock advance, refunds/chargebacks, gift payer attribution, sole monthly winner, zero donations, invalid input and configuration defaults/parsing.
- Both sandbox runtime award entry points pass the startup policy into the ledger. Existing permanent awards and separately labelled manual test awards remain intact. Sandbox Hall menus show the minimum.
- Artifact: `C:\Users\p_ric\Downloads\EnthusiaDonors-1.1.0-test.14.jar`. SHA-256: `E1900B8EAFC624710100ED7872646A3534A22ABA5810BE358A50EBB9D66F59F8`. ZIP audit confirms packaged GloriousPolicy.class, config.yml threshold and plugin.yml; copied artifact hash recorded.
- Local test implementation only: official Tebex monthly finalization, shared award history, LuckPerms grants and live Paper GUI acceptance are pending. No server upload/restart, commit, push or PR for this change. Existing proxy test.13 needs no code change for this eligibility rule.

## Real payment announcements — test.15

- SPEAR requirement/activation contract: payment-announcements.md. Initial PaymentAnnouncementsTest compile failed before notification types existed (announcements-red.log). Final focused suite contains 23 notification regressions; full clean package passed 200 tests with zero failures/errors/skips (announcements-build.log). Velocity clean package passed (announcements-proxy-build.log).
- Tests cover history/empty-baseline initialization, monthly-independent payment events, duplicate/restart/concurrent observation, legacy pending-to-complete parsing, reversals/free/manual/package/transaction exclusions, future/invalid identities, atomic rollback, durable independent claims, immutable payload retry, chat outage versus Discord progress, timeout/5xx uncertainty, bounded explicit 429 retry, expiry, public-safe rendering and strict publisher/relay configuration.
- Official successful complete reconciliations enqueue observations into the new opt-in adapter. Shared source identity survives publisher/key changes. Skin profile capture is scheduled on Paper's main thread; downloads and waits run off-thread. Pre-rendering occurs outside the shared observation transaction. Sandbox commands cannot reach the official adapter.
- Artifact audit confirmed all notification classes/config in the Paper JAR; proxy contains its MariaDB driver and no Paper sandbox classes. Signed relay protocol remains version 1.
- Paper: Downloads/EnthusiaDonors-1.1.0-test.15.jar, 676778 bytes, SHA-256 ECF0C0BBCDBC73D79011440E38EDDFBDCE061E6B22B6C6DCF166E09A559665B9.
- Proxy: Downloads/EnthusiaDonors-VelocityRelay-1.1.0-test.15.jar, 5508255 bytes, SHA-256 0CFD55384524A3D975B8978DA2AD9B6493A445666AE221EA553D9041A4C62DA0. Remote Desktop Commander checked the local artifacts.
- Verified existing webhook metadata and Discord channel read-only: Enthusia Purchases -> smp-chatter, channel 1410331662914555955. No webhook message was sent. The private existing URL is only in the inactive staged YAML outside this repository and on the same backend host; it is not logged or committed. Temporary Discord lookup credential file was removed.
- Staged on SMP 41f458f0: plugins/chapter 2 staging/EnthusiaDonors-1.1.0-test.15.jar, EnthusiaDonors-notifications-test15.staged.yml (notifications.enabled=false), EnthusiaDonors-test15-activation.md. First multi-file upload failed with Network Error; individual uploads succeeded and the final listing shows all files and expected sizes.
- Staged on Velocity 25319956: plugins/chapter 2 staging/EnthusiaDonors-VelocityRelay-1.1.0-test.15.jar and EnthusiaDonors-test15-activation.md. A proxy staging folder was created; existing active JAR/configuration were not replaced.
- Screenshots: ../artifacts/donor-test15-staging/backend.jpg and proxy.jpg. These prove remote file presence, not active plugin loading or client/Discord delivery. No restart, reload, test/real notification, commit, push, PR or production database provisioning occurred.
- Remaining acceptance: shared MariaDB schema/credentials and matching source/signing configuration, later approved plugin activation, baseline sync, subsequent actual payment shown across two backends and smp-chatter, live outage/shutdown recovery. Current official polling interval is 10 minutes; it can be reduced to 1 minute. Official Glorious finalization/permission grants are still separate work.
