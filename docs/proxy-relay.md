# Velocity donation relay — test contract

## Spec

- When an operator explicitly enables the relay network audience, the backend shall durably enqueue the complete rendered test announcement before reporting it queued. Test events shall stay separate from real Tebex payment processing.
- When the origin backend has no connected player, the database relay shall still publish queued announcements. No plugin messaging carrier shall be required.
- When shared storage is unavailable, the backend shall retain its local outbox and retry publication until the event's bounded expiry. It shall never fall back to MessageRaw after a relay failure.
- When a duplicate event is submitted, its immutable ID and payload shall prevent a second queue entry. Payload conflicts shall fail closed.
- When Velocity reads an event, it shall verify its source, expiry, size, version and HMAC before parsing and delivering its rich components. Clients shall have no plugin-message path to submit relay events.
- When a proxy claims an event, it shall persist one receipt for its stable proxy ID before any chat send. Concurrent workers and restarts shall not send the claimed event again automatically.
- When no player is connected to Velocity, it shall leave valid events pending until expiry. Recipients shall be the players connected when the event is claimed; later joins shall not receive old events.
- When the process crashes or any chat send throws after a claim, the event shall remain claimed/uncertain for inspection; automatic replay shall be forbidden because chat delivery is not transactional with database writes.
- When every send returns, the proxy shall mark its receipt delivered. A failure to record completion shall leave the claim in place and prevent replay.
- When the plugin is disabled, it shall stop background work and close resources. Credentials and HMAC keys shall stay out of logs and events.

## Architecture and limits

Backend local SQLite outbox -> MariaDB immutable event table -> Velocity poller -> proxy-connected players. Separate stable proxy IDs each receive one copy for their own audience. All instances serving the same audience must share a proxy ID. Database uniqueness guards claims; HMAC authenticates backend content with a network test key. Use separate producer/relay database users and TLS. Receipts expose delivered versus uncertain outcomes; they do not prove client display.

This slice connects existing explicit test broadcasts. Verified Tebex webhook ingestion and an outbox written in the payment transaction are later ledger work. No real payment notification is inferred from total changes. Existing MessageRaw behavior remains available only when the new relay is disabled.

## Prove plan

Write failing tests first for signed round trips/tampering/expiry, local restart/outage recovery, immutable event conflicts, independent proxy claims, concurrent claim exclusion, restart suppression and partial-send uncertainty. SQLite JDBC fixtures establish state/transaction behavior. MariaDB and live Velocity/Paper acceptance remain separate gates.

## Install on the test network

Install `EnthusiaDonors-VelocityRelay-1.1.0-test.13.jar` in the **Velocity plugins folder**. Install `EnthusiaDonors-1.1.0-test.13.jar` on Paper backends that originate test announcements. Backends receiving chat do not need an announcement receiver: Velocity sends directly to its connected players. Shared donor snapshots still require publisher/reader configuration on display backends.

The relay uses Java 21 bytecode and is compiled against Velocity API 3.4.0-SNAPSHOT. Its runtime MariaDB dependencies are packaged in the proxy JAR. The proxy entry point uses constructor injection and background polling; see the official [Velocity plugin guide](https://docs.papermc.io/velocity/dev/api-basics/) and [scheduler documentation](https://docs.papermc.io/velocity/dev/scheduler-api/). Runtime compatibility with the actual test proxy must be checked before use.

Start the proxy once to create `plugins/enthusiadonors-relay/relay.properties`, then set:

```properties
enabled=true
source-id=enthusia-donors-test
proxy-id=velocity-test-1
jdbc-url=jdbc:mariadb://DATABASE_HOST:3306/donors_test?sslMode=verify-full
username=donor_test_relay
password=SET_LOCALLY
signing-key=SET_A_RANDOM_KEY_OF_AT_LEAST_32_BYTES_LOCALLY
poll-seconds=2
initialize-schema=false
```

On each originating backend, append to existing `config.yml`:

```yaml
relay:
  enabled: true
  source-id: enthusia-donors-test
  backend-id: survival-test
  jdbc-url: 'jdbc:mariadb://DATABASE_HOST:3306/donors_test?sslMode=verify-full'
  username: donor_test_producer
  password: SET_LOCALLY
  signing-key: SET_THE_SAME_RANDOM_KEY_AS_THE_PROXY
  poll-seconds: 2
  expiry-seconds: 300
  initialize-schema: false
```

Use a different lowercase `backend-id` on each backend. Each proxy needs a stable `proxy-id`; replicas serving the same audience must share it. A changed proxy ID creates a new audience receipt and can receive any still-unexpired event. Use a dedicated test database, verify server TLS trust, and keep the signing key separate from Tebex credentials. Drain the queue before rotating keys; previously signed events will be rejected with a new key. Java properties uses escaping for backslashes, so write credentials carefully.

Provision the two fixed test tables once. One test instance can temporarily set `initialize-schema=true` with CREATE privileges; after creation, set it false and restart with reduced privileges. Backend producers need SELECT and INSERT on `enthusiadonors_test_events`. The proxy needs SELECT on that table and SELECT, INSERT, UPDATE on `enthusiadonors_test_receipts`. Snapshot reader accounts need their existing SELECT access separately; a reader account cannot act as an announcement producer. Neither relay role needs Tebex keys or donor/payment table write access.

Set `broadcast.allow-network-audience: true` in backend `testing.yml`. Restart for `config.yml` or proxy properties changes. `/edonors reload` can reload `testing.yml` only.

In-game: `/edonors test audience network confirm`, then use a preview/purchase/gift control. Console, including an empty backend: `/edonors test relaypreview FainNeito purchase confirm`. Console relaypreview creates only a test notice; it does not change payments or donor totals. Its single named person is both buyer and recipient; use the existing gift control with two targets for distinct gift attribution.

Backend replies report **queued**, not delivered. `/edonors status` shows outbox health. On Velocity, `/donorrelay` shows poll status, and `/donorrelay receipt <event-id>` reads that proxy's receipt. These commands require `enthusiadonors.relay.admin`; grant it only to operators. The console can run them too. `IDLE` does not prove remote database availability. A no-receipt result is not proof an event exists; inspect the event table and expiry when necessary.

Receipt states: `CLAIMED` is an unfinished/possibly sent event; `DELIVERED` means all server send calls returned; `UNCERTAIN` means a send failed after claim; `REJECTED` means validation/parsing failed. All receipt states suppress automatic replay. Local outbox states are `PENDING`, `PUBLISHED`, `EXPIRED`, `CONFLICT`; conflicting events are retained for inspection and do not block following events. Inspect the scoped `testing/relay/<endpoint-source-backend-hash>/outbox.db` for local failures. Do not delete receipts to retry a possibly sent event. This test slice keeps records for inspection; plan archival before production use.

## Live acceptance gate

1. Back up test JARs/config/data before installation. Keep relay disabled on production.
2. Start the configured proxy and two test backends. Keep the originating backend empty; connect a tester to the other backend. Run the console relaypreview command and check the complete face, styled text and clickable store link appear once.
3. Queue during a MariaDB outage. Verify no fallback chat, restore access within the 300-second expiry, and verify delivery once. Repeat with a backend restart while its outbox is pending. After expiry, verify no delayed chat.
4. Restart Velocity after a delivered receipt, then confirm no repeat. Start two workers with the same proxy ID against MariaDB and verify uniqueness of claims. Inject a send/completion failure and inspect `UNCERTAIN`/`CLAIMED`; verify no automatic replay.
5. Check distinct backend events, gift/subscription/renewal/Glorious templates, rejected signatures, player disconnects and transfers, and explicit network-audience permissions. Confirm simulation still cannot change public official totals.
6. Verify the actual Velocity/Paper versions and MariaDB dialect, TLS, accounts, and restart/shutdown behavior. Local SQLite tests and compilation cannot establish these live outcomes.

No real Tebex webhook notifications, payment-transaction outbox, deployment or PR are included in this slice.

## Local evidence

Spec and initial failing tests preceded implementation. Twelve relay tests and the full 161-test backend suite pass. Both JARs build, and isolated classloader verification confirms signed-envelope compatibility across the backend JAR and proxy JAR's relocated Gson. See [verification.md](verification.md) for exact hashes, packaging evidence and the live boundaries. No local SPEAR automation was present in this checkout; requirements, task phase and red/green evidence are recorded in these documents.
