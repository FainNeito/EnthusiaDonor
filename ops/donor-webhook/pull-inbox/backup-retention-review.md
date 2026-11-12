# Inbox backup-retention review — 2026-11-12

## Finding and evidence boundary

The 90-day completed-tombstone policy is not a raw-payment or backup deletion
guarantee. ACK removes body/signature from the current row; quarantine/pending
retain them without automatic expiry. Earlier snapshots can still contain those
bytes and can restore previously processed work. A restore must therefore remain
disconnected from the consumer until reconciled with the authoritative outbox.

Cloudflare's plan-specific limits list **7 days Free / 30 days Workers Paid** for
D1 Time Travel. Its general Time Travel page describes a 30-day recovery window,
says it is always on, and says restoring does not remove older bookmarks. Use
the plan-specific limit, not an assumed universal 30 days. No account plan,
production database/version, backup inventory or actual retention was inspected
or changed here: this project has not provisioned a hosted inbox.
[Plan-specific limits](https://developers.cloudflare.com/d1/platform/limits/),
[Time Travel documentation](https://developers.cloudflare.com/d1/reference/time-travel/).

Those documented recovery windows do not establish immediate physical erasure,
customizable per-record backup expiry, or contractual deletion timing. None of
those claims is made. If shorter backup retention or targeted erasure is required,
the available controls and terms need separate confirmation before selecting D1.

## Data inventory and proposed handling

| Copy | Current/proposed retention | Review consequence |
| --- | --- | --- |
| Pending/quarantined live rows | No automatic expiry | Requires owner-approved raw-data disposition; backlog can retain data indefinitely |
| ACKed live row | Payload/signature cleared immediately; digest tombstone default 90 days after ACK | Digest and timing are still metadata; do not label as fully anonymous |
| D1 Time Travel | Documented 7/30-day plan-dependent recovery window | ACK/prune does not erase earlier history; verify actual plan/version before hosting |
| Manual SQL export/download | None created; no scheduled export proposed | Any future copy needs encryption/access ownership and separately verified expiry/deletion |
| Restored validation copy | None hosted; local synthetic fixture only | Keep disconnected; proposed short-lived validation copy requires explicit approval and disposal record |
| Monitoring history | Proposed 14 days, aggregates only | External provider retention/access must be configured and verified; no payload/header capture |
| Existing authoritative outbox/ledgers | Existing policies, unchanged | Never delete/reset them to match inbox retention; they protect business deduplication |

Cloudflare documents SQL exports and notes that a running export blocks other
database requests. Do not add an automatic export job as a harmless backup check;
it creates another data copy and can affect ingestion. This task did not export
or download any live data.
[D1 import/export documentation](https://developers.cloudflare.com/d1/best-practices/import-export-data/).

## Restore runbook for later authorized acceptance

1. Record incident, approved scope, owner, actual D1 plan/version and chosen
   recovery point. Inspect metadata only first; a bookmark advancing alone does
   not prove application writes or a useful backup.
2. Establish a controlled ingestion/consumer boundary in a separately approved
   maintenance plan. Do not restore a database beneath a running consumer or
   silently reuse an old lease as current ownership. This document authorizes
   no pause, restart, restore or configuration change.
3. Validate recoverability in an isolated destination where supported and with
   no production bindings, consumer credentials, chat or Discord dispatch. A
   D1 restore operation is not assumed to create a harmless clone; verify exact
   provider behavior before issuing any command. No ready-to-run live restore
   command is included here.
4. Check schema/version, source, counts, payload-byte integrity and lease state.
   Compare recovered events against the **current** durable official-payment
   ledgers/outbox. Old snapshots may resurrect ACKed/deleted events, lose newer
   quarantine transitions, and omit payments accepted after the recovery point.
   Identify that gap and reconcile against authoritative records/provider
   delivery history; do not assume a restore alone has zero data loss.
5. Verify duplicate handling using synthetic events in the isolated path. Never
   bypass the signed handler/official lookup or directly reannounce recovered
   events. Old leases must be treated as stale and recovery reviewed explicitly.
6. Only after separate approval, execute the reviewed cutover and observe
   ingestion, backlog, deduplication and actual delivery. Record recovery-point
   loss and elapsed recovery time; no RPO/RTO SLA is established by local tests.
7. Dispose of temporary exports/copies according to the approved policy; record
   remaining provider history and verify provider-visible deletion controls.
   Do not claim that deleting a validation copy erased all historical backups.

## Local exercise and remaining gates

`test_backup_review.py` uses only synthetic SQLite data. It snapshots a leased
event, ACKs and prunes the live row, restores the older snapshot to a different
temporary file, and verifies that raw bytes/signature and deliverable work reappear.
It checks expired-lease rejection and demonstrates that the original live fixture
remains unchanged. Temporary files are removed after the exercise. This proves
the application-level hazard, not Cloudflare restore correctness or backup expiry.

The documentation review and local exercise are complete. Account-specific plan,
actual history/exports, owner-approved indefinite raw-data disposition, contractual
deletion requirements, hosted restore/duplicate/gap reconciliation, ownership and
recovery objectives remain unverified gates. No legal-compliance or erasure
certification is implied. PR #9 stays draft; nothing was merged, restarted,
provisioned, backed up, restored or activated in production.
