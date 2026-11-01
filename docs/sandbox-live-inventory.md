# Sandbox and live donor-network capability inventory

This compares the canonical `3c50f934` implementation with the requested
network platform. It does not assert that production acceptance has passed.

| Capability | Current live donor-network status | Remaining work |
| --- | --- | --- |
| Monthly and all-time totals, public profiles, heads and donor placeholders | Official Tebex data is published to shared snapshots; user reports leaderboards and profiles work. | Add reader backends where desired and verify same revisions; historical gift/purchase details are not in the shared projection. |
| Purchase messages | Opt-in real official-payment notifications and durable relay/Discord outbox are implemented and configured. | Verify a later real payment in network chat and smp-chatter; review duplicate Tebex broadcast commands. |
| Glorious monthly award | Sandbox-only calculation; no real award or LuckPerms grant. | Implement verified closed-month accounting, persistent idempotent award history, verified player binding and safe rank grant. |
| Devotee expiry | Sandbox simulates cancellation and expiry. | PR #3 adds optional reconciliation; stacked PR #5 adds Founder variant. Both need review, merge, verified mappings and audit before separately authorized enforcement. Tebex's normal expiry commands remain separate. |
| Gifts and gift history | Sandbox models buyer and recipient. Official public totals use Tebex payment records; the documented payment payload does not establish separate buyer and recipient for every gift. | Establish authoritative gift identity and history before publishing or making permission decisions; do not infer payer from recipient. |
| Renewal and purchase history | Sandbox simulates transitions and stores test history. | If requested as live profile features, add privacy-reviewed official data, retention, and shared projections; avoid guessing entitlement from payments alone. |
| Refund and chargeback simulations | Sandbox recalculates its own sample ledger. | Verify real Tebex state transitions and counting behavior across the live network; preserve permanent awards unless the user adopts a different policy. |
| Fake players, clock advance, reset, failures, mock skin/Discord previews | Operator-only test facilities. | Keep isolated from real payments, ranks, shared data and announcements; no live equivalent is required for normal players. |
| Automatic donor head emoji generation | Separate open PR #4 is unmerged and disabled. | Complete review, verified identities and TEST acceptance; publish packs only in an announced window. |

Glorious differs from an ordinary cosmetic display: a wrong player binding, a
late reversal, an incomplete payment page or two competing publishers can cause a
real permanent rank mutation. A complete, durable and fail-closed implementation
and isolated acceptance are required before any production activation.
