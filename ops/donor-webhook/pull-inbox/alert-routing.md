# Alert routing — local dry-run preparation

Requirement: prepare repeatable incident decisions without a live provider,
recipient or network side effect. Plan: test a pure state transition function,
then leave delivery and ownership bindings explicitly inactive.

`route(previous, sample, now, key)` in `alert-routing.mjs` returns new `state`
and sanitized `intents`. Time values are integer epoch seconds. Start with null
state; use `{code, checked_at}` for a new probe result and null for an independent
watchdog tick. Preserve checked_at on retries. Results older than 120 seconds or
already observed cannot advance streaks or conceal a missed-run incident.

- Three consecutive non-OK results open WARNING; two open CRITICAL/UNKNOWN.
  Mixed failures retain the highest observed severity until an OK result.
- Missing fresh results for 900 seconds opens UNKNOWN independently of the probe.
- An incident retains its ID across worsening and serialized state reloads.
  Unresolved incidents escalate to the backup role after 900 seconds, repeat at
  most hourly thereafter, and recover after three fresh OK results.
- Recovery reaches primary and, if escalated, backup. Only fixed reason codes,
  role labels, severity and timestamps are emitted; every intent is dry_run.
- Invalid state, cross-inbox state and backward clocks throw generic errors.
  Inputs are not mutated. There is no I/O, delivery adapter or running scheduler.

The future host must atomically persist state and an intent outbox, serialize
updates for each inbox, and retry delivery with the original dedup_key. It must
run the watchdog independently of the probe and preserve state across restarts.
Never reset corrupt state silently: surface an observer-health incident through
the independently supervised host. Delivery retries/receipts, responder
acknowledgements, access control and durable crash recovery are NOT implemented
by this function. No durable provider integration is claimed by local tests.

`monitor-plan.example.json` remains enabled=false and delivery_enabled=false;
provider, recipient and responder fields remain null. Role labels are not real
recipients. Later acceptance must bind those fields, verify storage/retention,
test crash/retry behavior and confirm receipt through an approved test channel.
That delivery/activation is outside the current authorization.

Five meaningful routing assertions first failed against an inert stub. All eight
routing tests now pass, covering escalation, mixed failures, recovery, missing
runs, reminders, safe output, replay/staleness and state validation. The full
local Node suite passes 35/35. CI repeats these tests without sending alerts.
