# Shared donor snapshots — first implementation slice

## Contract

Test.16 requires `runtime.mode: donor-network` for the opt-in adapter. Configure
`network.initialize-schema: false` after externally provisioning the projection
table; the publisher still registers the source through INSERT. Missing settings
retain automatic table initialization. Legacy runtime remains the default.

- When `network.mode` is `standalone`, the existing official Tebex read behavior shall remain available.
- When mode is `publisher`, only the process holding an unexpired database lease shall publish an official Tebex snapshot. The database shall atomically advance a revision and replace the complete public projection.
- When mode is `reader`, the backend shall perform only shared snapshot reads and local cache writes; it shall never call Tebex or create/modify shared database tables.
- When two backends read the same committed revision, GUI profiles, monthly/all-time leaderboards, and donor placeholders shall use the same donor totals, UUIDs, names, ranks, month, timezone, and display settings.
- When a shared read returns `NOT_FOUND`, it shall not be treated as a database failure or erase an existing valid snapshot. A committed empty snapshot is `FOUND` and may clear the display.
- When a shared read fails, the backend shall retain its last valid snapshot and report `FAILED`. Invalid payloads, another source, and older revisions shall not overwrite valid cached data.
- When the snapshot belongs to a previous calendar month, its monthly values shall be cleared and its all-time values retained with a stale status.
- When a backend restarts during an outage, it shall load an atomic local snapshot cache scoped to the shared endpoint and source ID. Database/Tebex credentials and raw payments shall never enter this projection.

## First-slice architecture

MariaDB holds one versioned JSON projection per test-network source in `enthusiadonors_test_projection`. The publisher boot token owns a renewable lease, timed by the database. Publication locks the head row, checks that token and lease, and commits the revision and payload together. Readers need SELECT access only. The publisher keeps its existing local official Tebex reconciliation store; this slice does not yet move raw payment accounting into MariaDB.

The default mode remains `standalone`. Network configuration is opt-in, with a separate test database/source. One configured publisher reads Tebex; additional publisher processes are fenced by the lease. Backends poll the committed snapshot asynchronously and serve existing GUI/PAPI adapters from their local immutable cache. The publisher also displays only shared committed data.

Public profiles in this slice contain the existing donor totals and ranks. Gift histories, purchase histories, verified webhooks, transactionally finalized Glorious awards, and durable proxy announcements are subsequent work. Network test messages still use the existing player-carried proxy channel.

## Verification plan

Use independent JDBC connections to prove shared revision visibility, lease exclusion/takeover, rejection of stale writers, atomic rollback, empty versus absent projections, malformed data rejection, source isolation, stale-cache preservation, and month rollover. Local SQLite JDBC fixtures prove the SQL/state contract; they do not prove MariaDB dialect/locking or live Paper behavior. MariaDB and two-backend acceptance must run in the shared test environment before activation.

## Test setup

Use a new, dedicated MariaDB test database. Add the following block to existing `config.yml` files; upgrades do not replace an existing configuration. Restart after changing these settings.

```yaml
network:
  mode: publisher
  source-id: enthusia-donors-test
  jdbc-url: 'jdbc:mariadb://DATABASE_HOST:3306/donors_test?sslMode=verify-full'
  username: 'donor_test_publisher'
  password: 'SET_LOCALLY'
  poll-seconds: 15
  lease-seconds: 60
  max-snapshot-age-seconds: 1200
```

The publisher needs CREATE, SELECT, INSERT and UPDATE privileges in that dedicated database, plus the official `tebex.api-key` and the established counting filters. Provision the database and accounts separately. Configure database TLS trust for the chosen host.

On each display backend, use the same endpoint and lowercase `source-id`, set `mode: reader`, and use a separate account with SELECT permission only on `enthusiadonors_test_projection`. Leave `tebex.api-key` empty on readers. Start the publisher first so it creates the test table. Readers adopt the publisher's timezone and display settings. This build shares donor totals/ranks; player kill/death statistics and sandbox state remain local.

Use `/edonors test status` to inspect mode, source, revision, read state and publisher lease. `/edonors test refresh` polls on readers and reconciles Tebex on an active publisher. A standby publisher reconciles on its next scheduled refresh after acquiring the lease. Initialization retries after database recovery. Lease epochs reject a fetch that began before an expired lease was reacquired. Snapshot month and totals use the same reconciliation instant.

## Local verification — 2026-10-01

SPEAR red: the initial shared-projection tests failed compilation before the network implementation existed. Green: `mvn clean package` built `EnthusiaDonors-1.1.0-test.12.jar`; 149 tests passed, including 10 new network tests. Coverage includes independent JDBC readers, empty/absent projections, competing owners, expired owner takeover, same-token lease fencing, transaction rollback, bad/source-mismatched payloads, monotonic revisions, future timestamps, month rollover, default standalone configuration and credential-safe status text.

The projection fixture uses SQLite, not a MariaDB server. MariaDB SQL/privileges/TLS, startup library resolution, disk-cache restart recovery, GUI/PAPI integration on Paper and two-backend agreement are **not verified live**.

Before activation, compare all-time/monthly totals with official Tebex, then check `/donors` and donor placeholders on two Paper backends at the same revision. Transfer between servers and repeat. Stop database access, verify cached displays remain and status reports failure, restart a reader during the outage, restore access and verify a newer revision. Test simultaneous publisher starts, publisher loss and takeover on MariaDB. Exercise month rollover with a controlled test clock/data set. Keep this JAR out of production until those checks pass.
