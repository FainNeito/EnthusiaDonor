# Local route verification — 2026-11-11

## Tested source and tools

- Canonical main / receiver source: `b9edd9b78f5ef3cd011b81f377e1bc450e066e8e`.
- PR #8 checked open/unmerged at `cf6025fb8d2e4bb4db96ec234e4953717eee71b2`;
  not included in this branch's test results.
- Windows host; Eclipse Temurin JDK 25.0.5+8; Maven 3.9.11; Python 3.12.
- Official NGINX Windows 1.31.8 archive:
  `https://nginx.org/download/nginx-1.31.8.zip`.
- Archive SHA-256:
  `fbb1501009be34655cc238d460714cf20391b639013f4c73859813d7938783fd`.
  Downloaded over vendor HTTPS; digest recorded locally, not a claim of
  independent PGP signature verification or Linux release validation.

## Outcomes

| Check | Result |
| --- | --- |
| Maven test-compile | Passed; actual main-branch receiver compiled |
| Deliberately unbuffered control | Failed as expected: `Incomplete body reached upstream` |
| NGINX transport plus real receiver suite | 22 passed, 0 failed/errors; 31.733 seconds |
| NGINX configuration syntax | Passed for each rendered test configuration |
| Existing Maven tests | 231 run; 230 passed, 1 skipped, 0 failures/errors |

The skipped Java case is the optional external MariaDB fixture. No MariaDB
instance was started here. The existing Java suite includes synthetic replay,
deferred outbox and fake-Tebex integration coverage; it does not prove production
database behavior or live delivery.

Test transcripts are retained in the task's review bundle as `route-negative.log`,
`route-green.log`, `receiver-tests.log` and `baseline-build-online.log`. Fixture
runtime files are ignored under `target/route-fixtures`; they are not deployment
artifacts. The local test launchers stopped their owned NGINX and Java processes.

The red control was deliberately introduced into a temporary config after the
candidate existed. It demonstrates test sensitivity to missing buffering, not a
pre-implementation run. Earlier failures from missing NGINX temp directories,
sandbox-denied loopback sockets, and an incomplete chunk-size-only test request
were resolved and are not counted as meaningful regression evidence.

## Review and remaining limits

Self-review checked raw URI matching (including normalization aliases), duplicate
signature handling, body preservation, global rather than spoofable per-client
budgets, explicit upstream concurrency, no automatic retry, safe log fields,
loopback-only fixtures and owned-process cleanup. No independent reviewer approval
is claimed. The test code deliberately has no live credentials or delivery bridge.

Not established by these tests: Linux/Java 21 compatibility, deployment supervision,
SMP namespace reachability, TLS/Host behavior through Cloudflare and Bloom,
origin filtering, absolute total upload deadline, legitimate burst sizing,
live webhook retries, real gift identity, production MariaDB, Paper startup,
network-wide chat or Discord acceptance. See `route-plan.md` for release gates.

Production state: no restart, reload, port allocation, DNS write, live config
change, webhook registration, payment event, notification or artifact upload.
