# Donor test-build placeholder repair

## Scope and evidence

The test.6 JAR shows raw `%enthusiadonors_*%` tokens on donor and kill/death holograms. Test.7 added PlaceholderAPI but incorrectly used simulated sandbox donors for public ranks. Test.8 adds a read-only Tebex payment path and an isolated local cache. Production R2 and LuckPerms write paths remain disconnected.

## EARS requirements

- When a public donor leaderboard, profile, or `enthusiadonors` donor placeholder is requested, the test build shall use only payments read from the official Tebex Plugin API and the existing counting policy; simulated sandbox purchases shall never affect those public values.
- When the official Tebex key is missing or the first fetch has not completed, public donor displays shall identify the source as unavailable/loading rather than present sandbox values as real donations.
- When a Tebex refresh fails after a valid refresh, public donor displays shall retain the last valid official snapshot with a stale/error status; an incomplete page set shall not replace it.
- When an operator uses `/edonors test` controls, test purchases, gifts, previews, and virtual month behavior shall remain isolated from official Tebex totals, public donor profiles, and R2/LuckPerms writes.
- When PlaceholderAPI is installed, the test plugin shall register a persistent `enthusiadonors` expansion after its sandbox snapshot is ready.
- When PlaceholderAPI is absent, the test plugin shall start without linking or loading PlaceholderAPI classes.
- When a display requests a documented donor placeholder, the expansion shall resolve it from the current immutable official Tebex snapshot, including all-time, monthly, player, count, and status fields.
- When a display requests a documented kill/death placeholder, the expansion shall resolve it from local Bukkit player statistics held in a separate test-only stat store.
- When a leaderboard position has no entry, the expansion shall provide the documented empty display values rather than leaving a raw placeholder token.
- When `privacy.show-amounts` is false, the expansion shall suppress formatted and raw monetary values.
- When the plugin reloads or shuts down, the expansion shall use current settings and release its runtime resources.
- When a real donor's GUI head is shown, the menu shall resolve the player profile texture without requiring broadcast skin-image download, then write the textured item back into the open inventory. Synthetic test identities may retain the neutral head.
- When Mojang's profile service is slow or unavailable, head and preview lookups shall time out within the configured bound, retain previously cached textures, and avoid Paper's `PlayerProfile.update()` path that emits authlib timeout traces. A first-time offline head may remain neutral until a texture can be fetched.
- When an official donor has a valid current Java username, the texture reader shall resolve that name with Mojang before trying the Tebex payment UUID; an operator shall be able to inspect the result without exposing payment data or API keys.

## Glorious monthly minimum

- The monthly #1 donor must contribute at least the configured `glorious.minimum-monthly-donation` during that calendar month to earn Glorious. Default: $30.00; equality qualifies.
- Sum qualifying payments credited to the payer within the existing timezone/month window. Previous months, refunds, chargebacks, and zero-dollar payments do not satisfy the minimum.
- If the leader falls below the minimum, close the month without an award. Preserve existing permanent awards and separately labelled manual test previews.
- Reject negative, malformed, sub-cent, or overflowing configuration values. Read this policy from config.yml at startup; restart to apply changes.
- Apply the rule to both explicit test month closure and virtual-clock advancement. Official Tebex month finalization remains pending and must use this same policy when connected.

## Verification boundary

The shared data contract is in [network-data.md](network-data.md). The signed Velocity announcement/outbox contract and delivery uncertainty policy are in [proxy-relay.md](proxy-relay.md). Both are opt-in test features; real Tebex event ingestion and payment-transaction announcements remain separate ledger work.

Unit tests prove value mapping, source separation, and empty/privacy behavior. A clean Maven build and JAR inspection prove compilation and packaging. A live Paper server with a configured Tebex key, PlaceholderAPI, and the hologram plugin is still needed to prove the official totals and player-visible replacement.
