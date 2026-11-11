# Anarchy Frontier mode

## Current rules: test.10 (supersedes earlier claim/pickup instructions)

- `/claim [name]` previews a chunk; `/claim confirm` establishes a Founding Vault (guild chest or solo barrel). Further claims expand edge-connected land from that founding chunk; no additional bells or vaults are placed. Ordinary bells are decorative.
- All chunks owned by a guild or solo owner share one territory name. `/claim rename <name>` updates it everywhere. Entry titles show that name with the guild/owner beneath it.
- Packing requires `/vaultpickup`, a prominent all-land warning, a three-second reading delay, then `/vaultpickup confirm-unclaim-all` within 30 seconds. It surrenders ALL owned claims before removing the vault. Stored contents are retained. Packing is prohibited during raids and does not start a replacement cooldown. Only hostile raid defeat starts the 30-minute cooldown. Guilds with land cannot disband to bypass the objective.
- Raid objectives have 20 damage points, with a two-second accepted-hit interval. Exposed TNT explosions deal four points. Outsider mining inside claims runs at 35% normal speed during raids. Wither explosions, skulls, and direct Wither block destruction cannot damage claimed terrain or objectives; the mob itself remains available.
- Four empty chunks separate unrelated owners, except for a temporary attacking guild `/raidclaim` staged edge-adjacent to enemy territory. Claims are overworld-only. Overworld/Nether borders are 5000 blocks, Nether travel is 1:1 and the roof is blocked. End border is 512 blocks and gateways are disabled. Spawn reservation is configurable and currently zero.
- Migration unifies legacy claim records/names, replaces the primary solo bell with a barrel, removes obsolete bells, and releases old guild land whose vault was already packed. Existing disconnected land is retained but cannot be used to extend away from the founding territory. The founding record supplies territory permissions.
- Automated validation: 201 selected Frontier/claim/partition/vault tests passed, zero failures or skips. Two-player testing remains on hold; mining feel, particles, UI and live combat still need player acceptance.
- Pre-update world and plugins backup: `archive-2026-11-10T062742Z.tar.gz` on Bloom.

## Earlier design notes (current rules above take precedence)

This document defines a separate LumaGuilds ruleset for the Anarchy Frontier server. It must not change the existing SMP profile, configuration, database, or player data. The server will run its own LumaGuilds instance and data store.

## Server shape

- Small survival map: 5,000 × 5,000 blocks, centered around `(0, 0)`.
- Use EnthusiaFrontier for frontier generation/lifecycle management; it is not the terrain generator.
- Spawn near the center, with a modest randomized offset from `(0, 0)`. Keep spawn and its routes out of player claims.
- Semi-anarchy survival with light guild conflict and no economy/progression loop.

## Claims

- Claim units are full Minecraft chunks (16 × 16 blocks, full build height), never arbitrary rectangles or block counts.
- Support personal claims and guild claims. Personal allowance remains available to guild members; guild allowance is a separate shared pool.
- Keep ownership, build/container permissions, chunk map/boundary visualization, claim info, entry alerts, and a recent activity log.
- Prevent overlap, alternate-account allowance multiplication where practical, claim fencing, and blocking spawn/public infrastructure.
- Claims are protected outside the daily raid window. During the window, protection is suspended while ownership remains intact.
- Claim state must recover correctly after a restart during an active raid window.

## Raid window and alerts

- A scheduled daily window makes claims vulnerable. Schedule/timezone and duration are operator-configurable.
- Announce the window and send countdowns before opening and closing. Display current protection state and remaining time.
- Alert claim owners when an outsider enters; for guild land, notify online defenders/officers.
- Alert defenders when an outsider takes a hostile action in a claim during the raid window. Entry by itself is informational, not a raid attempt.
- Rate-limit repeated alerts and log actor, time, chunk, and action.
- Define explicitly which interactions are allowed during a raid: PvP, container access, block damage, fire, TNT, and other explosives.

## Guild vault objective

Implementation status: the shared claim-release backend and startup recovery are implemented. It snapshots the affected IDs into an atomic file record before deleting claims, retries unfinished cleanup after restart, and skips claims transferred to a different owner.

Frontier now routes player block-break events for registered vaults and primary solo anchors through durable siege damage. Provisional defaults are 20 accepted hits, at least two seconds apart, and a 30-minute replacement cooldown after cleanup. Defender hits and attempts outside the raid window are rejected; unfinished damage expires with that window. Vanilla objective drops are cancelled. Vault removal preserves stored items, then releases the recorded guild claims. Solo destruction removes the recorded bell and releases personal claims only. Recovery starts after worlds load and retries every ten seconds, including outside the raid window. These paths have automated tests but still require isolated in-game validation.

- Each guild has one registered physical vault objective in its guild territory.
- Vaults provide all 54 ordinary storage slots immediately, without level upgrades or a gold-currency button. Raw gold remains a vanilla item; Frontier currency costs are disabled.
- Vault damage is possible only during the raid window and generates a high-priority defender alert.
- When the vault is destroyed, release all of that guild's claimed chunks. Personal claims are unaffected.
- A guild may establish a replacement vault after a cooldown and reclaim chunks within its normal allowance.
- Vault contents and the destroyed objective are separate concerns: destruction of the claim objective must not silently duplicate or delete stored items.
- The vault must have durable, auditable raid progress; it should not be an ordinary one-hit block.

## Personal claim objective

- Each player with personal claims has one registered personal claim anchor in a personally claimed chunk.
- The anchor follows the same raid-window rules, defender alerts, and staged-damage expectations as a guild vault.
- When the anchor is destroyed, release all chunks in that player's personal claim pool. Guild-owned chunks are unaffected.
- The player may establish a replacement anchor after a cooldown and reclaim chunks within the normal personal allowance.
- Personal claims remain available to guild members and use the same protection/raid schedule as guild claims; joining a guild does not transfer or erase them.
- The anchor is an objective, not a bank: destroying it must not by itself delete the player's inventory or physical vault contents.

## Keep from LumaGuilds

- Guild creation, membership, a small role set, guild identity/chat, and basic guild permissions.
- Existing guild claim ownership integration, after adapting claims to chunk units.
- Physical vault storage and its audit/persistence protections, separated from the new vault-as-raid-objective state.

## Disable for this profile

- Guild XP, levels, prestige, quests, and progression rewards.
- Guild bank, fees, taxes, claim purchases, and economy-driven upgrades.
- Guild-home teleport progression, outposts, allied waypoints, and instant travel conveniences.
- Rated wars, seasonal ratings, peace/hostile mode switching, and complex diplomacy.
- Recruitment board/application extras and unrelated SMP systems such as market, mail, donor rewards, reputation, and Warzone duels.

## Combat restrictions

- Disable end crystals, elytras, and respawn anchors for this mode.
- Use MaceGuard for mace restrictions and relevant mace combat protections; do not duplicate its enforcement in LumaGuilds.
- Enforce remaining item bans across crafting, use, placement, equipment, and indirect damage paths.

## Chat dependency decision

- Include RoseChat on the Frontier server and retain LumaGuilds' existing guild-channel integration.
- Use a dedicated Frontier chat configuration. The locally built API dependency does not imply that an SMP chat configuration should be copied wholesale.

## Implementation sequence

1. Add a disabled-by-default `ANARCHY_FRONTIER` ruleset/config section. Keep all current behavior unchanged when it is not selected.
2. Add chunk-native claim operations and separate personal/guild chunk allowances; disable rectangular partition resizing in this mode.
3. Add scheduled raid state, restart recovery, claim-entry/raid-attempt alerts, and audit records.
4. Add distinct guild-vault and personal-anchor raid objectives with staged damage, raid-window gating, separate claim release, and replacement cooldowns.
5. Add the Frontier server config and integrate item restrictions with MaceGuard and the existing server stack.
6. Validate on an isolated test server, including persistence/restart, negative chunk coordinates, Java/Bedrock UX, and an in-progress raid at shutdown.

## Existing implementation notes

An isolated test package is generated by `ci/Prepare-FrontierTest.ps1`; see `docs/FRONTIER_TESTING.md` for startup and acceptance steps. It includes the pinned Paper runtime, a separate fresh world/database profile, RoseChat, Vault's API bridge, and EnthusiaFrontier with cleanup disabled. The profile configures a 5,000-block border, safe offset spawning, reserved spawn claim exclusion, item restrictions, and a restricted guild command surface. XP awards are disabled in Frontier. Siege hit count, timing, and replacement cooldown are now configurable. `/raidinfo` shows raid timing and the primary solo anchor. Guild conversion requires an available vault in the first converted personal claim or existing guild land; a member's personal claim can host the initial vault for this setup flow.

Development status: `claim_geometry: CHUNKS` snaps claim creation, placement validation, expansion, and resizing to whole chunk columns. It is intended for a fresh database. Creation, expansion, resizing, guild conversion, and player-transfer acceptance enforce separate personal/guild allowances. Configurable development defaults are 4 personal chunks and 16 guild chunks. Legacy UI still displays some capacity feedback in blocks (256 per chunk); command/UI replacements remain to be implemented.

`frontier.enabled` defaults to false. When enabled with CHUNKS geometry, `frontier.raid` selects the timezone, daily start, and duration. The daily window permits mining, building, container access, doors, redstone, and harvesting through the claim permission layer. Registered objectives use the Frontier siege rules; other fire/explosion flags and PvP integration still follow existing paths. The complete combat policy remains pending; this is not a release-ready server profile. Restart after schedule/configuration changes.

The Frontier listener displays raid timing, announces opening/closing and five/one-minute warnings, reports outsider entry to online owners/guild members, and reports raid-time block damage, container-access attempts, and attacks on defenders. Entry and raid alerts have independent 30-second cooldowns per claim and actor, and accepted alerts are written to the server log. A player-facing persistent raid history and defender-role filtering remain pending.

The claim-release backend persists the original ownership snapshot before deleting claims and resumes interrupted cleanup on startup when Frontier is enabled. Personal releases exclude guild land; guild releases include land placed by different members. Recovery skips claims that now belong to another owner. Siege callbacks can submit their persisted release plan directly, so retrying a completed deletion does not include newly acquired claims.

Replacement checks now guard vault placement, personal claim creation, anchor movement, conversion into guild land, incoming/outgoing personal transfers, and guild disbanding. Locks apply during active damage, pending cleanup, and the post-defeat cooldown. Legacy anchor deletion is disabled in Frontier. The oldest personal claim (creation time, then UUID as a tie-breaker) supplies the solo owner's primary anchor; other personal bells and guild claim bells cannot be raided directly. Entity/block explosions, burning, and environmental block destruction cannot bypass vault siege damage. Existing bell environmental protections remain in place. Staff vault-removal commands remain administrative tools; they do not count as raid defeats. Richer objective discovery UX and live-server acceptance testing remain to be completed.

Current LumaGuilds claim geometry is block-based rectangular partitions. `Partition.getChunks()` derives chunks from those rectangles; it does not make the claim itself chunk-native. The existing physical guild vault already has confirmation and item-preservation behavior. The new mode therefore needs an explicit claim-unit model and a distinct vault-objective lifecycle, while leaving the SMP behavior as the default.
