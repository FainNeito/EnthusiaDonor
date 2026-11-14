# Bloom placement and transport verification — 2026-11-12

**Result: not ready for production activation.** This read-only inspection does
not establish a working donor endpoint. No server commands, settings writes,
port allocations, restarts, support messages or live webhook events occurred.

## Observed configuration

| Surface | Observation | Evidence boundary |
| --- | --- | --- |
| Bloom SMP / Ports & Connections | Primary 25565, Guilds API 2052, Agent 25577; 3/10 allocations | No donor allocation shown; free slots do not identify a free port number |
| Bloom SMP / Startup | Java 25; command runs Java with `-jar server.jar`; editable fields are JAR, RAM percentage, JVM flags and program arguments | No independent supervisor exposed here; not proof that Bloom cannot provide a supported arrangement |
| Cloudflare / DNS search `donations` | No matching record | Proposed hostname is not configured in the inspected zone |
| Cloudflare / SSL/TLS Overview | Current mode Full; automatic mode disabled | Zone default is not Full (strict); does not establish all possible hostname-specific overrides |

The existing authenticated dashboard pages were used. No credential pages,
payment payloads or live console contents were needed.

## Placement

The actual Java receiver enforces loopback binding. NGINX must therefore share
its network namespace to reach `127.0.0.1`. The Java startup page does not establish
supported multi-process supervision, shutdown or recovery. Do not inject shell
commands into Java flags or use an interactive background process as supervision.

Bloom documents inter-split connectivity using server IDs and services listening
on container interfaces. That does not expose a loopback-only receiver. A separate
NGINX split is consequently not a drop-in solution; changing the receiver's bind
would require a separately reviewed isolation/authentication design.
[Internal networking](https://docs.bloom.host/internal-servers)

Creating a split reallocates resources and restarts the main server according to
Bloom. It is outside this task's no-restart scope. No split was created.
[Splitting servers](https://docs.bloom.host/split-server)

## Transport by segment

| Segment | Established | Still required |
| --- | --- | --- |
| Tebex to Cloudflare | Proposed URL uses HTTPS; no donor hostname is active | Certificate/client acceptance and no interactive challenge on the dedicated route |
| Cloudflare to Bloom | Full zone mode; Bloom documents HTTPS website connections | Validate origin certificate identity/validity and suitable strict validation for the donor host |
| Bloom proxy to SMP ingress | Allocation-backed website routing exists generally | Protocol, encryption, certificate validation, network isolation and direct-origin restrictions |
| SMP NGINX to receiver | Local/Linux fixture uses loopback and real HMAC | Actual same-namespace placement and supported lifecycle |

Cloudflare Full mode does not validate the origin certificate's hostname, expiry
or trust. For HTTPS callers it encrypts that hop, but does not close the
origin-authentication gate. Full (strict) requires a trusted, unexpired, matching
certificate. Verify compatibility and exact scope before proposing a future
change; no shared TLS setting was changed here.
[Full](https://developers.cloudflare.com/ssl/origin-configuration/ssl-modes/full/),
[Full (strict)](https://developers.cloudflare.com/ssl/origin-configuration/ssl-modes/full-strict/)

Bloom's website guide describes CNAME/ownership setup and managed certificates.
The inspected sections do not specify backend protocol/certificate validation,
source ACLs, buffering, upload deadlines or retries. These are unknowns, not
claims that Bloom lacks those protections.
[Website connections](https://docs.bloom.host/ports-and-connections#websites)

DNS proxying and Host matching do not authenticate an ingress caller. HMAC
protects body authenticity, not confidentiality or unauthenticated resource use.
Obtain a supported private route/source restriction rather than assuming a
public allocation accepts only Bloom traffic. No requests bypassing Guilds or
Agent access controls were made to investigate the donor route.

NGINX has tested buffering/admission bounds but only an idle body timeout.
Cloudflare's documented 30-second origin proxy-write timeout is not proof of a
total client-upload deadline across every layer or against direct-origin access.
The Bloom total-upload bound remains unverified.
[Cloudflare connection limits](https://developers.cloudflare.com/fundamentals/reference/connection-limits/)

## Questions prepared for Bloom — not sent

1. Is a supervised NGINX process supported in the **same network namespace** as
   the Paper/Java 25 process, with a dedicated ingress allocation forwarding to
   a loopback-only receiver? What startup/shutdown/recovery mechanism is supported?
2. What protocol/network path connects the managed website proxy to an allocation?
   Is it encrypted/authenticated? Can it validate backend TLS or reach private ingress?
3. Can the ingress accept only managed-proxy traffic at the network layer? What
   source ranges/identity, IPv6 handling and updates apply? Can other splits or
   tenants bypass that path?
4. What header/body sizes, buffering (including chunked requests), absolute/idle
   deadlines and concurrency/rate limits apply? Are Host and raw body preserved?
5. Does the proxy retry POST on failures, timeout or 5xx, and can retries be
   disabled? Do its certificates support Cloudflare Full (strict) during issuance
   and renewal?

These capability questions need no credentials or payment data. No support
message was sent; sending one requires the user's instruction.

## Next validation boundary

After capabilities are established, use an authorized disposable topology to
verify supervision/recovery, same-namespace access, each protected transport hop,
direct-ingress rejection, signature preservation, buffering/deadlines and retry
behavior. Do not run slow-client/oversize/concurrency probes on shared production.
The 23-test Linux Java 21/25 passes at `db457225a7572b4da2dc4f5b9e260cce230e2e30`
remain component evidence, not Bloom managed-layer acceptance.

Keep production placeholders unrendered and the webhook disabled until these
gates are resolved. No DNS placeholder, port opening, Guilds Access weakening,
shared TLS change or SMP restart is authorized. PR #9 remains draft/unmerged.
