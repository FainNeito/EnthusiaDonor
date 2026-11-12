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
   authenticated events. Retry backoff, atomic capacity limits, quarantine,
   completed-tombstone retention and aggregate attention status are now implemented
   in the [inbox policy](pull-inbox/policy.md). Unprocessed events never expire
   automatically. External alerting and hosted capacity sizing remain open.
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
and its lifecycle/configuration tests are now implemented as described below.
Startup selects exactly one transport; both remain disabled by default. The pull
consumer starts only after a successful official sync establishes the baseline
and eligibility snapshot.

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

## Implemented preparation slice — 2026-11-12

The Worker entry module now uses the same SQL through a D1 binding. It requires
HTTPS, exact POST paths without queries, independently configured signing and
consumer secrets, and a trusted SOURCE matching the publisher namespace. It
checks the signature before parsing, accepts only the existing official lookup's
transaction-reference format, and awaits a successful insert before payment ACK.
Validation handshakes are authenticated but do not create inbox rows. Uploads
have a 262144-byte limit and five-second total read deadline. Consumer requests
are limited to 1024 bytes. No raw event or credential is logged by this code.

Claims use server-generated 256-bit tokens and 90-second leases. ACK requires the
current unexpired token and clears the live payload. A lost ACK response is safe:
if committed, the tombstone suppresses replay; otherwise the event is redelivered.
A repeated ACK returns 409 and never consumes another owner's lease.

The Java adapter uses ordinary verified HTTPS, no redirects, a five-second
connect timeout, ten-second full response deadline, 350000-byte response cap,
raw-body digest checks, and the existing signed endpoint. It waits up to thirty
seconds for processing before sending ACK. Timeout/failure leaves the lease for
redelivery; any processing still in flight remains covered by shared outbox
deduplication. Pulls are serial, normally every five to seven seconds after each
completed cycle; failures back off to at most sixty-two seconds. Closing stops
scheduling and cancels owned HTTP work. No acknowledgement is scheduled after
processing finishes on a closed consumer. Already transmitted requests cannot be
recalled.

Configuration under `notifications.tebex-webhook`:
- `enabled` selects the existing loopback receiver (default false).
- `pull-enabled` selects the outbound consumer (default false); both true is invalid.
- `inbox-origin` is an HTTPS origin without credentials, path, query or fragment.
- `consumer-token` is a separate 32–256 character URL-safe secret, never an account API key.
- `secret` remains the Tebex signing secret; `wait-seconds` retains its existing meaning.
- Pull mode needs no listening port and only starts following a successful publisher sync.

Worker bindings are DB (dedicated D1), SOURCE, TEBEX_SECRET and CONSUMER_SECRET.
Only local Miniflare bindings exist here. There is deliberately no deploy script,
Wrangler deployment configuration, live resource identifier or stored secret.

## Verification and remaining acceptance

See [inbox implementation evidence](pull-inbox/evidence.md). Local Node tests
cover authentication, signed validation, delayed/failed persistence, unsupported
inputs, exact routes and read deadlines. Local workerd/D1 exercises 256 KiB BLOB
serialization, duplicate ingestion, concurrent leases, expiry, stale ACK,
payload clearing, replay tombstones and missing-table failure. SQLite's eight
protocol tests are retained. Java tests cover commit-before-ACK, lost ACK followed
by one durable outbox job, invalid envelopes, processing errors, shutdown, config
exclusivity and a trusted local HTTPS gateway with certificate/hostname rejection,
redirect refusal, response cap and stalled-body deadline. These are synthetic,
isolated tests, not a hosted end-to-end deployment or independent approval.

The [capacity, quarantine and retention policy](pull-inbox/policy.md) now sets
atomic admission bounds, isolates exhausted expired leases, prunes old completed
tombstones, and exposes authenticated attention status to the consumer. Pending
and quarantined payloads are preserved without automatic expiry. Before
activation, validate account capacity/cost, establish external alerting and
operator ownership, resolve the account-specific gates in the
[backup-retention review](pull-inbox/backup-retention-review.md), and implement
operational credential rotation. An inactive [external monitoring adapter and
integration proposal](pull-inbox/monitoring.md) is now prepared. Verify actual account quotas/billing, hosted D1
behavior, Bloom outbound egress, publisher failover and full payment-to-delivery
acceptance under a separately authorized isolated hosted plan. The existing
late-enrichment and gift-identity limitations remain.

PR #9 stays draft. The prior NGINX fixture is reference evidence, not a dependency
of this outbound design. PR #8 remains separate. A later reviewed plugin release,
configuration and authorized lifecycle step are still necessary. No merge, server
restart/reload, DNS update, Worker/D1 creation, secret creation, Tebex registration,
notification or production activation occurred.
