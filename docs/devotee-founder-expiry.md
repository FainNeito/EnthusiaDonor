# Founder-variant subscription reconciliation

## Spec

The `devotee` and `devotee-founder` groups are independently assigned variants of
the same paid subscription. An expired subscription must not remove a player's
other memberships or alter a shared group's inheritance graph.

The operator must verify that `devotee-package-ids` includes every Tebex package
that can confer either targeted subscription. Do not infer a package mapping from
the group name. Multiple active configured packages protect both variants.

- When an account directly holds a positive, permanent, global `devotee` or
  `devotee-founder` membership, the reconciler shall identify that exact group as
  a candidate and apply the existing verified customer, paid-history, active
  package and confirmation checks before suggesting or making a removal.
- When both variants exist, the reconciler shall process them separately and
  use a fresh group-specific confirmation window for each target. A pending
  observation for one group shall not authorize removal of the other group.
- When the targeted subscription is removed, Founder, Avid, Media, Glorious,
  staff and all unrelated direct and inherited groups shall remain untouched.
  Their inheritance may still provide Devotee gameplay permissions.
- When a protected UUID or an explicitly configured protected group applies,
  the candidate shall remain held. Existing explicit protected-group choices
  must remain effective; enabling removal of Founder-variant memberships must be
  an explicit setting, with the current conservative defaults retained.
- When target-group membership is missing, negative, contextual, temporary,
  duplicated, or changes between verification and mutation, it shall be held.
- When the target is the primary group, a verified remaining direct group or
  safe direct default parent shall be established before removal. Never choose
  the very subscription membership being removed as the fallback.
- When any bound customer has an active package, a lookup fails, identity is
  uncertain, or paid history is absent, no subscription node shall be removed.
  Audit, confirmation, shutdown and uncertainty safeguards remain mandatory.

## Prove / engine / architecture / refine

Reproduce missing-founder candidate coverage and unsafe cross-variant pending
reuse before implementing. Test target selection, overlap, primary fallback,
explicit protection, identity/source failure, exact concurrent-node mutation,
restart persistence and network invalidation. Keep domain policy independent of
LuckPerms; use the existing official Tebex source and persistence adapter.

Work starts from PR #3 head `b0fed04c9902746292614d6fd65c1f3fd1869ba9`
and current canonical main `3c50f93486464dc8b90dd04345ee0101cdcd1254`.
This is an isolated source checkout. No project-local EARS/state helper exists;
this requirement and test evidence record the SPEAR state. TEST, production,
deployment, activation, server restarts, pack reloads and merges are outside the
current scope. Review and real account acceptance remain separate.

## Configuration and compatibility

`entitlements.reconcile-founder-variant` defaults to false. The separately
configurable `entitlements.allow-direct-removal-from-protected-groups` also
defaults to false and overrides only built-in inherited-group holds. Explicit
`protected-groups` and `protected-uuids` always continue to apply. The shipped
configuration explicitly lists protected groups; an operator must deliberately
adjust that list for the requested accounts before cleanup can proceed. When both options are enabled, the
operator is allowing removal of the specific expired subscription node for a
Founder, Media or Glorious holder; all other nodes and shared group definitions
remain unchanged. Higher ranks may still grant inherited perks. Existing saved
confirmation records lack a target-specific fingerprint and will restart their
confirmation period after this update.

## Current evidence

Existing source directly searches only `group.devotee` and holds every inherited
Founder group, so it cannot handle a direct `devotee-founder` expiry. New
regression cases and implementation are prepared in this isolated checkout.
An isolated dependency-free probe failed to compile against the old candidate
API, then compiled and passed against this work for separate variant confirmation,
active-entitlement protection and rejected unsupported targets. Evidence is in
`operations/donor-founder-expiry-20261031/`. This is only a domain probe; the
LuckPerms adapter and settings tests then ran in the complete offline Maven
verification with Java 25: 277 tests passed, with no failures, errors or skips.
The initial full test run exposed two uninitialized LuckPerms fixture errors;
the fixtures were corrected and a user-limit test was strengthened to assert
the actual bound failure. The separate run logs are retained in
`operations/donor-founder-expiry-20261031/`. Hosted checks and review remain pending.
No Paper, player, or live-rank acceptance is claimed.
