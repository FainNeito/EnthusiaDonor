# Missed Devotee expiry reconciliation

## Spec

Canonical base fetched 2026-10-15: `3c50f93486464dc8b90dd04345ee0101cdcd1254`.
The user requests automatic cleanup of missed subscription removals, with no
additional permanent direct Devotee exceptions. No restart/reload is authorized.

- When reconciliation is enabled on one designated Paper backend, it shall check
  direct, permanent, global Devotee holders, including offline users, every
  configured interval. It shall default to audit mode and be disabled by default.
- When the official customer-purchases endpoint explicitly returns no non-expired
  configured Devotee package, and matching paid Devotee history and Minecraft
  identity are verified, it shall record an inactive-entitlement observation.
  An empty successful response is distinct from missing/error/malformed data.
- When the same customer/payment fingerprint remains inactive on separate checks
  across the configurable confirmation interval, it shall recheck all evidence
  immediately before removing only the exact direct permanent global membership.
- When ANY configured Devotee package is active (including overlapping purchases),
  data is unavailable, identity is uncertain, or paid history is absent, it shall
  retain membership and reset pending observations. Declined payments, cancelled
  renewals, guessed 30-day terms, and donation totals shall not authorize removal.
- When a user inherits Founder, Media, Glorious or another configured protected
  group, has a contextual/temporary Devotee grant, or is explicitly excluded, it
  shall preserve access. It shall never remove Avid or inherited memberships.
- When verification or pre-mutation persistence fails, it shall preserve access. It shall
  journal intent durably before a write and record success or uncertainty after
  LuckPerms save. It shall not blindly restore after an uncertain save.
- When stopped, it shall prevent new mutations. Network and storage work shall
  run off the Paper main thread. A local file lock shall prevent concurrent
  reconciliation owners using the same data folder.

## Authority and limitations

Tebex documents `GET /player/{customer-id}/packages` as all active **non-expired**
packages. This is an entitlement reconciliation, not a guess from billing dates
or a claim to read subscription lifecycle statuses. It protects any active
Devotee purchase regardless of renewal status. Paid history limits cleanup to
verifiable purchases; manual grants without that history remain untouched.

The API's customer ID can differ from the Minecraft UUID. Resolve the Minecraft
name against Mojang and LuckPerms independently, then use the ID returned by
Tebex player lookup for package queries. Renamed, gifted or Bedrock identities
that cannot meet this proof remain held for review. No broad name-only removal.

There is no atomic transaction spanning Tebex and LuckPerms. The last check
reduces, but cannot eliminate, a purchase racing the eventual save. Keep Tebex's
normal purchase/renewal grants and expiry command enabled. Run one enforcement
owner for the shared LuckPerms database; other backends need no reconciler.

A save that fails or times out after an in-memory LuckPerms mutation is uncertain:
it may or may not have committed, and the local cache may already reflect removal.
Record `UNCERTAIN` rather than claim success or blindly overwrite concurrent
permission changes. An operator must reconcile that audit entry with the shared
store. This is separate from an API outage, which never authorizes removal.

## SPEAR state

Spec written before implementation. Prove: focused tests will exercise expiry
confirmation, active/overlapping packages, outages, identity/history failures,
restart persistence and protected memberships. Engine: domain policy with
injected ports; official HTTP and Paper/LuckPerms adapters stay in infrastructure.
Arch/refine: validate optional linking, lifecycle, audit persistence and exact
runtime API compatibility. No local EARS/state tools exist; Markdown is the
requirement/task/evidence record. No historical red/green evidence is invented.

## Activation and acceptance

Prepared configuration for this store (not applied):

```yaml
entitlements:
  enabled: true
  mode: audit
  delivery-server-id: 2147009
  devotee-package-ids: [7053397]
  check-interval-minutes: 15
  confirmation-minutes: 60
```

The adapter uses the existing private `tebex.api-key`; do not copy a secret into
Git. Start with this on only the SMP backend after the release/activation gates.
Audit mode writes `entitlement-reconciliation/audit.jsonl`, with `HELD_ACTIVE`,
`HELD_UNKNOWN`, `HELD_VERIFICATION_FAILED` and `WOULD_REMOVE` outcomes by Minecraft
UUID. The scheduled summary includes held/waiting/proposed/removed counts. First
absence starts confirmation; the earliest proposal is after 60 minutes, not the
first 15-minute pass. Interruption of three poll intervals resets confirmation.

Review the candidates and test enforcement on isolated infrastructure before
changing `mode` to `enforce` under a separately authorized activation. Switching
audit to enforce starts a fresh confirmation window. No extra proxy JAR is needed
for this feature; the committed change propagates through LuckPerms messaging.

Rollback: disable this adapter for a later authorized startup, preserving its
pending file and audit. For a confirmed erroneous removal, restore only the
recorded user's direct group and prior primary group after checking current
entitlements and intervening administrative changes. An `UNCERTAIN` record needs
store/cache verification before any restoration. Never restore all donor groups
from an old snapshot or resend purchase/broadcast commands.

Source must be reviewed/merged and built cleanly before any production staging.
This implementation does not activate on currently running servers. First run
audit mode against the real store and inspect held/removal decisions. Then test
enforcement using an isolated server/account with independent LuckPerms storage,
including offline expiry, renewal, overlap, outage and save failure. Enable one
production enforcement owner only after a separately authorized activation.

Sources:
- https://docs.tebex.io/plugin/endpoints/customer-purchases
- https://docs.tebex.io/plugin/endpoints/player-lookup
- https://docs.tebex.io/plugin/endpoints/payments
- https://docs.tebex.io/creators/tebex-control-panel/payments-overview/recurring-payments
