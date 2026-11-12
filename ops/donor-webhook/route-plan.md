# Dedicated donor webhook route — preparation only

Prepared 2026-11-11 (America/New_York). No production changes are authorized by
this file. No DNS, Bloom allocation, server startup, plugin configuration,
Tebex webhook registration, or notification dispatch was changed in this work.

## Source and workflow

The fresh canonical main is `b9edd9b78f5ef3cd011b81f377e1bc450e066e8e`.
PR #8 remains open at `cf6025fb8d2e4bb4db96ec234e4953717eee71b2`.
This preparation is isolated on `codex/donor-proxy-fixture`, based on main; it
neither merges nor incorporates PR #8. Before activation, review and merge the
receiver hardening separately, then repeat integration tests against the exact
clean merged release. No production artifact was built or staged here.

Follow the project's documented SPEAR gates in `docs/delivery-workflow.md`.
No installed SPEAR skill was found in the local skill/plugin catalogs searched.
No automated SPEAR validator is claimed. The requirements, plan, test evidence,
review limitations and future cutover/rollback steps below are the trace.

## Proposed topology and unresolved placement

```text
Tebex HTTPS POST https://donations.enthusia.info/tebex
  -> Cloudflare (dedicated hostname; no interactive Access challenge)
  -> Bloom managed HTTPS website proxy (dedicated connection)
  -> dedicated NGINX ingress port in SMP's network namespace
  -> 127.0.0.1:<private donor receiver port> in that SAME namespace
  -> existing signed endpoint / authoritative lookup / durable shared outbox
```

Cloudflare inspection found no `donations` DNS record. Bloom inspection found
only primary 25565, Guilds API 2052 and Agent 25577. The existing Guilds CNAME
points to `revproxy-us.bloom.host`; it is not a donor endpoint. No existing port
or Access policy is to be repurposed.

The application explicitly refuses non-loopback binds. A proxy on another
Bloom split/container or VPS cannot use SMP's `127.0.0.1`. **Placement is still
blocked** until Bloom supports a separately supervised NGINX process sharing
SMP's network namespace, or a separately reviewed transport design provides
equivalent isolation. Do not change the Java bind to `0.0.0.0` to bypass this.

Two distinct ports are needed: one dedicated Bloom allocation for ingress and
one verified-free private loopback receiver port. Neither number is assigned
by this template. Only ingress would be publicly allocated. The fixture always
renders both addresses as `127.0.0.1` with ephemeral ports.

Confirm with Bloom before a cutover:

1. Supported process supervision, startup ordering, restart and failure handling
   for the extra proxy in the same namespace. NGINX must not depend on an
   interactive terminal staying open.
2. Reachability from managed proxy to the dedicated ingress, Host preservation,
   upstream HTTP/TLS behavior, transport confidentiality, source filtering and
   prevention of direct origin bypass. Do not assume a managed TLS edge encrypts
   its downstream hop or restricts direct access to the allocation.
3. Header/body buffering, request-size limits, absolute upload/header deadlines,
   rate/burst limits and retry behavior of both managed layers. Application
   response time can approach 15 seconds; upstream timeout alignment matters.
4. Actual available port, returned CNAME target and any TXT ownership challenge.
   Use the values shown for the new connection; do not copy another service's
   ownership token or assume the regional target is permanent.

## Requirements and test mapping

| ID | Requirement | Fixture coverage |
| --- | --- | --- |
| R1 | Only exact host + POST `/tebex`, no query/normalization aliases | wrong routes/methods/hosts |
| R2 | Bound headers and body to 256 KiB; reject ambiguous framing | oversize header, fixed/chunked body, exact boundary, duplicate length and TE+CL |
| R3 | Complete body buffered before contacting receiver; preserve signed bytes | partial fixed/chunked, aborted upload, UTF-8/whitespace forwarding |
| R4 | Reject malformed/duplicate signature metadata before upload; real HMAC remains in Java | metadata rejection, real signed validation, changed-body rejection |
| R5 | Bound stalled headers/bodies, route admission and backend concurrency | stalled/recovery, ninth incomplete upload, burst 429, two active upstreams |
| R6 | Never acknowledge failed processing or automatically retry at proxy | upstream 503 passthrough; overloaded upstream 503; disconnected bridge 503 |
| R7 | No payment/identity/credential logging or unrelated headers forwarded | synthetic sensitive header/log checks; safe access-log fields |
| R8 | Fixture has no live side effects | ephemeral loopback sockets; synthetic secret; null dispatch bridge; owned-process cleanup |

## Configuration review

`nginx.conf.in` is a full dedicated-process template, not an include for a shared
proxy. It cannot run until socket placeholders are replaced. Only the test
harness renders it, and only for localhost. Production rendering remains manual
and gated by the topology checks above.

- One worker, 128 connection slots (including upstream connections), no keepalive.
- Host header is mandatory, including HTTP/1.0; normalized hostname must match.
- Header timeout 3 seconds; initial 1 KiB and two 4 KiB large header buffers.
- Body size 262144 bytes, full request buffering, 3-second **idle** body timeout.
- At most 8 requests after full headers; global 5 requests/second plus burst 10.
  Global route limits deliberately do not trust forwarded-IP headers and need
  tuning against measured legitimate Tebex retry bursts before activation.
- At most 2 active upstream connections; no retry/failover; connection timeout
  1 second, upstream write inactivity timeout 3 seconds, read inactivity timeout
  20 seconds. Unavailable/saturated upstream returns 503, not success.
- Explicit Host, Connection, Content-Type and X-Signature headers only. Signature
  shape checks are NOT authentication. Java verifies exact raw body bytes.
- No cache. Access logs contain status, duration and upstream status only.
  Error logging is critical-only, which trades diagnostics for reduced request
  exposure. Protect and rotate logs; do not enable debug logs on live payloads.
  Protect body-temp storage: unusual buffering/spooling can leave sensitive data
  on disk temporarily even though payloads are excluded from logs.

Limits are bounded-resource defenses, not a complete denial-of-service solution.
`limit_conn` only counts requests once headers are complete. Worker connection
limits and header timeout provide the earlier bound. NGINX `client_body_timeout`
is inactivity-based: a client dripping bytes within that interval can retain an
admission slot. The template does **not** impose a total upload deadline or prove
fairness under hostile load. A verified outer absolute deadline or equivalent
bounded occupancy policy remains a production gate. Windows NGINX tests do not
prove Linux epoll behavior, host firewall rules, TLS or Bloom proxy behavior.

## Reproduce locally

Prerequisites: Java 21+ JDK, Maven, Python 3.10+, an explicitly selected NGINX
binary. Use an isolated checkout. Commands below are run from the repository root.

```text
mvn --batch-mode test-compile
python ops/donor-webhook/test_route.py --nginx <absolute-nginx-binary> --work target/route-fixtures --negative-control
python ops/donor-webhook/run_fixture.py --nginx <absolute-nginx-binary> --gson <absolute-gson-2.11.0.jar>
mvn --batch-mode test
```

The negative-control run must fail specifically with `Incomplete body reached
upstream`; a dependency or startup error is not meaningful red evidence. It
disables buffering in a temporary copy only. The normal suite must exit zero.
The Java launcher uses the actual compiled `TebexHttpReceiver` and
`TebexWebhookEndpoint` with a fixture-only secret and a null bridge. Signed
validation can succeed; signed payments must fail closed without invoking a
database, official API, Discord or player announcements. Existing Java tests
separately cover the synthetic outbox/replay paths.

The `Donor route isolation` workflow repeats this harness on Linux with Java 21
and 25, using a source-archive-digest-pinned NGINX build in the runner's temporary
directory. It also requires the buffering control to fail for the specific
expected assertion, so a startup/dependency failure cannot count as a passing
control. Inspect exact-head results; merely adding the workflow is not evidence
that its tests passed. See the follow-up in `route-evidence.md`.

Each test starts and stops its own NGINX instance under a unique prefix; it never
uses a system NGINX service or a blanket process kill. Temporary test data remains
under `target/route-fixtures` for inspection. The harness is not a deployment tool.

## Verification evidence

See `route-evidence.md` for exact versions and outcomes. The deliberately
unbuffered control was run after creating the candidate and tests; it is mutation
evidence, not a claimed historical pre-implementation red run. Initial startup
issues (NGINX temp directory and sandbox socket access) were fixture/environment
failures, not behavior regressions. A chunked oversize test initially sent a chunk
length without any chunk data and hit the idle timeout; adding the first data
byte exercised the intended 413 rejection.

## Future activation and rollback — NOT executed

1. Review this route and PR #8; resolve placement, total deadline, downstream TLS
   and origin filtering. Run this suite on the supported Linux NGINX version and
   Java 21/25. Build only clean merged source, record hashes and applicable pins.
2. In an isolated same-topology environment, prove process supervision, failure
   recovery, exact signed payload preservation, retry behavior, source correlation,
   MariaDB locking and no duplicate outbox jobs. Use synthetic store events and
   disabled real delivery channels. Verify gift semantics separately.
3. Before seeking activation approval, prepare the actual ingress/private ports,
   exact hostname/CNAME/TXT, private config diff, reviewed artifact hashes,
   snapshots of affected settings and maintenance/restart window. Keep webhook
   disabled until that explicit cutover. Do not change the existing source ID,
   notification ledger, Guilds/Agent allocations or Guilds Access policy.
4. After approval only: configure dedicated route/ingress and private receiver,
   register the hostname with Bloom, add only the required DNS records and
   complete certificate/Host/origin tests. Bloom says new ports require restart.
   Never weaken zone-wide SSL or Access settings to make this endpoint work.
5. Enable/register only after baseline readiness. Use one controlled validation,
   then controlled event/retry/replay tests. Correlate receipts before allowing
   real player/Discord delivery; a 200 validation response is not full acceptance.
6. Roll back by disabling webhook delivery at the sender and restricting/removing
   the new route, then disabling the receiver through the supported approved
   lifecycle. Restore only task-specific changed config/artifacts. Preserve the
   notification source, seen ledger, HELD/PENDING/CLAIMED jobs and receipts.
   Disabling enrichment may later release held fallback jobs: inspect them before
   resuming dispatch. Treat uncertain claims as uncertain; never blindly resend.
   Port removal also requires a later approved restart. Verify polling health
   and absence of duplicate notifications after rollback.

## References

- [Bloom ports, website connections and restart behavior](https://docs.bloom.host/ports-and-connections)
- [NGINX request buffering and upstream timeouts](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_request_buffering)
- [NGINX header/body limits and timeout semantics](https://nginx.org/en/docs/http/ngx_http_core_module.html#client_body_timeout)
- [NGINX connection counting boundary](https://nginx.org/en/docs/http/ngx_http_limit_conn_module.html)
- [NGINX Windows limitations](https://nginx.org/en/docs/windows.html)
