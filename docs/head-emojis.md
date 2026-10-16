# Donor head emoji queue

## Spec (SPEAR)

- When enabled on a designated backend, the adapter shall consume only a fresh
  official, counted donor snapshot, never fake/sandbox entries or inherited ranks.
- When an eligible owner has no glyph, the adapter shall resolve the exact
  Minecraft profile, compose its 8x8 head, and stage a unique username shorthand,
  stable UUID glyph ID and unoccupied private-use character in Nexo source files.
- When the Tebex UUID differs from the Minecraft UUID, the adapter shall require
  an independently verified `head-emojis.verified-players` mapping; current names
  alone shall never authorize an account permission. Bedrock/unresolved profiles
  shall remain pending rather than receive a substituted Java player's head.
- When a glyph is staged, the adapter shall grant only its owner's cosmetic
  permission with the configured LuckPerms server context; it shall not grant ranks.
- When an emoji is added, the adapter shall never call Nexo reload, regenerate,
  upload or dispatch a resource pack. Operators publish during an announced window.
- When files or services fail, existing glyphs shall survive and uncompleted work
  shall retry. Registry corruption and codepoint/alias collisions shall fail closed.
- When the plugin stops, the adapter shall stop accepting work and retain staged
  files and pending publication state. Existing owners keep stable characters.

## Architecture and activation

Pure queue policy accepts counted snapshot candidates. Infrastructure owns Nexo
YAML/PNG persistence, bounded Mojang HTTPS reads, LuckPerms and Bukkit scheduling.
This optional adapter is disabled by default and independent of notifications and
subscription reconciliation. Enable on one backend per Nexo source directory.
TEST uses permission context `server=test`; a shared global grant is forbidden.

The manual TEST grants cover owners in the existing 147-icon pack and are cosmetic
access, not a purchase entitlement audit. Future additions use official data.
Existing source glyphs are adopted without replacement or character reassignment.
The generated resource pack remains unchanged until a separate operator action.
New emoji shorthand works only after the planned config/pack publication.

## Delivery boundary

Current main: 3c50f93486464dc8b90dd04345ee0101cdcd1254. Isolated feature branch;
PR #3 subscription work is not included. No project-local EARS/state helper exists;
this document, tasks and test evidence track spec -> prove -> engine -> arch -> refine.
No server restart or production activation is authorized by this source change.
