# Outbound inbox preparation evidence — 2026-11-12

This records the initial implementation at `dd4974a`. The subsequent
[policy slice](policy.md) adds tested capacity, quarantine, retention and status
behavior; its counts and remaining gates supersede the corresponding items below.

## Spec and implementation

[Requirements, design, activation boundary](../outbound-inbox-plan.md).
Worker source: `entry.mjs` / `worker.mjs`; shared storage statements: the SQL
files in this directory. Java integration: `TebexInboxConsumer`,
`TebexIngressSettings`, `PaymentAnnouncements`, `TebexWebhookAssembly`.
Default configuration disables both transports. No production resources exist
as a result of this work.

## Prove / verify

- Worker tests first ran against a deliberately closed 503 stub. Authentication,
  routing, signed validation, consumer and ACK assertions failed; the deferred
  persistence test timed out because storage was never invoked. Storage-error
  assertions passing against that stub do not prove behavior. No historical
  regression is claimed. Captured transcript: `target/worker-red.log`.
- Java consumer's four initial tests all failed against a no-op stub: processing
  never started, ACK sequencing was absent, and unsafe origins were not rejected.
  Captured transcript: `target/pull-consumer-red.log`. An earlier sandbox/cache
  access error was environmental and is not counted as behavioral red evidence.
- Implemented Worker: 11 Node/local-workerd tests, including an actual local D1
  binding and synthetic 262144-byte BLOB. This is stronger than the earlier SQLite
  prototype, but is not hosted Cloudflare acceptance. Deadline/cancellation
  behavior is directly tested with Node streams; local D1 uses workerd.
- Retained SQLite protocol suite: 8 passed. Java targeted suite: 8 passed.
  HTTPS fixture generates a temporary self-signed certificate; only its injected
  test client trusts it. Production uses normal system trust and hostname checks.
- Full local backend `mvn verify`: 239 run, 238 passed, 1 optional external
  MariaDB test skipped. JDK 25, compilation target 21. Java 21/25 builds and the
  retained NGINX suite run separately in PR CI; current results are in PR #9.
- Pinned Miniflare 4.20260730.0 is test-only. Its vulnerable transitive versions
  were overridden to undici 7.30.1 / sharp 0.35.5; installation audit reported
  zero vulnerabilities. Lockfile is committed. No install/deploy credentials.

## Review and unresolved gates

Self-review tightened upload memory use: one whole-upload race and a fixed-size
buffer avoid retaining a timeout reaction and allocation for every tiny chunk.
Consumer startup waits for a successful official baseline sync. Transaction
admission matches the existing official Tebex lookup's accepted reference shape.
SOURCE syntax matches publisher source IDs. Tests and CI do not replace review:
PR #9 has no submitted reviews or inline findings at this check, and draft bot
review was previously skipped.

Capacity policy, quarantine/backlog alarms, retention (including backups), hosted
behavior, real egress and complete payment-to-delivery/failover acceptance remain
open. Pending rows do not expire automatically. A timed-out handler can finish
later; no delayed ACK is issued, and redelivery relies on the existing durable
outbox. Existing late-enrichment and gift semantics remain unchanged.

No merge, server restart/reload, production upload, DNS/port/Cloudflare setting
change, Worker/D1 provisioning, secret creation, webhook registration or live
notification was performed. PR #9 remains draft; PR #8 remains separate.
