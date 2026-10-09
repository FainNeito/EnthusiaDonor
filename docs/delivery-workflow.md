# EnthusiaDonor delivery workflow

## Authority and adoption — 2026-10-04

The user's standing agreements apply to this plugin. Canonical source is
https://github.com/FainNeito/EnthusiaDonor, default branch `main`.
Explicitly fetched main: `eede9199d0ff6156ec4a6b05e46f1749e60f3eb4`.
This checkout previously fetched only `codex/donor-test6`; future work must
explicitly fetch and inspect the default branch before choosing a base.

Review branch: `codex/donor-network-review`, isolated under
`workflow-review/EnthusiaDonor-network`. Existing sandbox/privacy commits and
uncommitted test.7–test.15 source were transferred without changing the original
`EnthusiaDonor-placeholder-fix` checkout. Source remains unmerged and under review.

## SPEAR gates

1. **Spec:** Read requirements.md, tasks.md, network-data.md, proxy-relay.md,
   payment-announcements.md and verification.md. Preserve official Tebex authority,
   sandbox isolation, privacy, existing options and permissions.
2. **Prove:** Retain prior regression evidence with its original scope. Re-run the
   existing backend suite and proxy build after source transfer. Do not invent
   historical red/green evidence. MariaDB and client acceptance remain separate.
3. **Engine:** Make the smallest supported fixes in this canonical repository.
   Keep domain/application logic independent of Paper/Velocity/database adapters.
4. **Arch:** Review API compatibility against actual companion runtimes before
   integration delivery. Review transaction, deduplication, failure, rollback and
   shutdown behavior. Existing CI currently builds Paper only; proxy checks and
   artifact publication must be covered before a combined release is approved.
5. **Refine:** Resolve actionable findings, update evidence, and deliver through a
   reviewable PR. Record its exact head, checks and unresolved live acceptance.

No project-local EARS validator or SPEAR state helper was found. The existing
Markdown requirement/task/verification files are the evidence record; no automated
EARS/state validation is claimed. No behavioral change was made when adopting this
workflow, so a new failing test is not applicable to that documentation step.

## Release and operational gates

- A local build or staged JAR is not a completed canonical fix.
- Before another production upload or activation, verify the merged main commit,
  build from a clean checkout through the canonical release path, and record
  version, SHA-256, CI and deployment evidence for both artifacts.
- If a monorepo owns the release, update its dependency/submodule pin through its
  normal PR process and verify the combined build. This checkout has no
  `.gitmodules`; external build ownership still needs confirmation before release.
- Workflow adoption does not authorize merging, server changes or messages.
  The user's no-restart constraint remains in force.
- Previously staged test.15 artifacts came from unmerged local work and remain
  inactive test artifacts. Their file presence is not runtime acceptance.

## Open delivery work

Test.16 updates: publisher initialization can avoid DDL; legacy startup/export
behavior is restored by default and included in the JAR. The newer adapter must
be explicitly selected with runtime.mode=donor-network. Both local builds and
205 backend tests pass. The dedicated restricted database exists, but local SQL
access is denied and server-side access/schema provisioning remain pending.

- Review accumulated source and update stale task/evidence wording.
- Add canonical proxy CI/release coverage and establish runtime compatibility.
- Verify official rankings against Tebex and PlaceholderAPI/hologram behavior.
- Complete shared MariaDB setup and two-backend/Velocity acceptance under a
  separately authorized operational plan; verify real chat and smp-chatter receipt.
- Complete official Glorious finalization, permanent shared award history and
  permission integration. The configurable $30 policy currently covers sandbox
  closure/clock advancement only.
- Publish the reviewed PR; inspect exact-head checks and findings. Merge and
  production delivery require their own authorization.
