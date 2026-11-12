# Recommended alternative: outbound donor inbox

Decision proposed 2026-11-12: remove Bloom from the incoming webhook path. A
Cloudflare Worker receives signed Tebex events and persists them in a dedicated
D1 inbox. The plugin initiates authenticated HTTPS pulls and feeds raw bytes into
the existing transport-independent endpoint/notification bridge. No inbound port,
NGINX process, cloudflared process, Bloom TLS proxy or support ticket is needed
for this architecture. No component is activated by this document.

```text
Tebex --HTTPS POST--> Cloudflare Worker --binding--> dedicated D1 inbox
                                      ^
                                      | HTTPS claim / acknowledge
                                      | initiated by the plugin
                              EnthusiaDonor publisher
                                      |
                       existing official lookup + shared outbox
                                      |
                         existing chat / Discord dispatch
```

Cloudflare Custom Domains make the Worker the origin, with managed DNS and
certificates. There is no Cloudflare-to-Bloom origin hop in this design; the
current zone Full setting need not be changed to reach a Bloom backend.
[Worker Custom Domains](https://developers.cloudflare.com/workers/configuration/routing/custom-domains/)

The plugin must use ordinary outbound HTTPS with certificate/hostname validation,
bounded responses/timeouts, redirects disabled for credential-bearing requests,
and a fixed configured endpoint. Actual egress to that endpoint still needs
verification; existing Tebex polling is not proof of access to every destination.

## Why a D1 inbox

Cloudflare supports HTTP pull consumers, but Queues' 128 KB message limit is
smaller than this receiver's 262144-byte cap even before serialization overhead.
Using Queues would require rejecting some currently valid bodies or storing
payloads elsewhere and queueing references. Neither is necessary for a small
dedicated inbox. [Pull consumers](https://developers.cloudflare.com/queues/configuration/pull-consumers/),
[Queue limits](https://developers.cloudflare.com/queues/platform/limits/)

D1's documented 2,000,000-byte BLOB/row limit can hold a 256 KiB body plus small
metadata. Bind raw bytes; never interpolate body text into SQL. The prototype
uses ordered parameters supported by D1. Size compatibility is not proof of
hosted D1 behavior or cost. Account quotas, capacity, request rates and retention
must be checked before provisioning; nothing was purchased or provisioned.
[D1 limits](https://developers.cloudflare.com/d1/platform/limits/),
[Bindings](https://developers.cloudflare.com/d1/worker-api/prepared-statements/),
[Pricing](https://developers.cloudflare.com/d1/platform/pricing/)

## Required behavior before implementation can be accepted

1. Worker `/tebex`: exact POST route, bounded streamed body with a total read
   deadline, strict signature metadata, HMAC over the exact raw bytes, bounded
   parsing and admission. Answer authenticated validation requests separately.
   Only supported authenticated payment events enter the inbox. Return success
   only after durable insert succeeds; storage failure/quota exhaustion must
   return a retryable error. Never acknowledge via a fire-and-forget background
   task. Duplicate authenticated inserts are safe successes.
2. Store namespace comes from configuration, not request data. Use raw-body
   digest as an inbox key; this deduplicates identical deliveries, not all
   representations of the same payment. Existing authoritative payment identity
   and shared outbox remain responsible for business deduplication.
3. `/consumer/claim`: authenticate a separate random, per-store consumer secret
   before any read. No Cloudflare account administrator token belongs in the
   plugin. Lease one event atomically with a server-generated random token and
   database-clock expiry. Bound all caller input and response size. Use no-cache
   responses and no payload/signature/credential logs.
4. Plugin: asynchronously pull at a bounded interval (initial candidate five
   seconds, with outage backoff/jitter), reverify original Tebex signature, then
   invoke the existing endpoint/bridge in-process. Do not bind a local HTTP server
   merely to call it. Retain authoritative Tebex lookup, eligibility, publisher
   policy and the existing notification store. A queued event is not proof of
   payment validity, gift identity or player delivery.
5. `/consumer/ack`: only after successful durable outbox processing or a durable
   duplicate result. Require the current unexpired lease token. Crash after commit
   but before ACK causes redelivery, which the outbox must deduplicate. Never
   directly announce from the inbox consumer. Transient failures must not ACK.
6. Separate quarantine/inspection behavior is required for repeatedly failing
   authenticated events. Add retry backoff, capacity limits, backlog alarms and
   a retention policy; do not silently expire unprocessed events. On quota failure
   stop accepting new events successfully. The prototype does not yet implement
   these operational policies.
7. ACK removes raw body/signature from the live row and retains a digest tombstone.
   Configure retention for tombstones and backups separately: clearing live
   columns does not erase historical backups. Use a dedicated private database,
   least-privilege bindings, no customer-data dashboards and no real fixtures.
8. Pull and HTTP receiver runtime modes must be mutually exclusive, default off.
   Reuse the same source/outbox across modes. Stop scheduled work on plugin
   shutdown. Do not change source IDs or replay historical backlog during cutover.

The existing `TebexWebhookEndpoint.handle` accepts bytes/signature/time without
requiring a socket, and `WebhookNotificationBridge` already writes through the
shared outbox. These are viable integration points, but a new outbound adapter
and its lifecycle/configuration tests are still required. Current startup wires
the loopback receiver; this proposal does not change that wiring.

The current 120-second enrichment window still applies. Offline/backlogged
renewals may arrive after fallback messages are released; pulling does not solve
late enrichment or establish gift buyer/recipient semantics.

## Local prototype evidence and limits

`pull-inbox/` contains schema, single-statement enqueue/claim/ACK SQL and eight
SQLite tests. Before implementation the tests failed because the schema file
did not exist (eight setup errors, not a behavioral regression). After adding
the SQL and adapting to D1-compatible ordered parameters, all eight passed in
0.315 seconds. The tests exercise separate SQLite connections, including two
concurrent claim attempts. They cover payload preservation at 262144 bytes,
duplicate enqueue, lease expiry/redelivery, stale/expired ACK rejection,
store isolation and payload erasure with replay tombstones.

Run from repository root:

```text
python ops/donor-webhook/pull-inbox/test_storage.py
```

This is a **SQL protocol experiment**, not a Worker, D1 binding test, deployed
queue, authenticated gateway or wired plugin consumer. It uses only synthetic
local data. Authentication, D1 serialization/concurrency, bounded body reads,
cross-store credentials, hosted failure recovery, database capacity/retention,
poller/outbox crash ordering and the full path still need implementation/testing.

## Next implementation slice and activation boundary

Implement the Worker/D1 binding and private claim/ACK API in local workerd tests;
then add the opt-in Java pull adapter against a fake HTTPS gateway and the existing
durable outbox. Test lost responses, crash after commit, conflicting poll/webhook
orders and publisher transitions before an explicitly authorized isolated hosted
test. Keep PR #9 draft while this replaces the proposed production ingress design;
retain the NGINX fixture as reference evidence, not an activation requirement for
the outbound alternative.

No support dependency or new Bloom port is required, but this is not a zero-change
activation. A later reviewed plugin release/configuration and approved lifecycle
step are still necessary. No merge, server restart/reload, DNS update, Worker/D1
creation, secret creation, Tebex registration or production activation occurred.
