# Frontier isolated test server

## Raid-claim feature branch (pending isolated acceptance)

- During the daily raid hour, an authorized guild officer (both `MANAGE_CLAIMS` and `PLACE_WAR_BANNER`) stands in an **unclaimed** chunk directly **edge-adjacent** to another guild's permanent land and runs `/raidclaim`. `/raidclaim <target guild>` resolves a corner with more than one neighboring guild. `/raidclaim confirm` creates one temporary forward claim; `/raidclaim cancel` dismisses the preview.
- The attacking guild has at most one active one-chunk raid claim. It ignores the regular empty-chunk separation rule **only for this temporary position**. Diagonal adjacency, solo-land targeting, other dimensions, occupied land, spawn reservations, and out-of-border chunks are rejected.
- Place the guild war banner **inside that chunk** within two minutes. `/raidclaim status` shows its state; `/raidclaim abandon` removes an **unbannered** staging claim. The banner is not placeable outside the active forward claim.
- A banner-bound claim lasts only while that exact banner is active. Destroying the banner, its normal 15-minute expiry, or **raid-hour close** ends the forward claim. **The physical war banner is also removed and its active SQL state deactivated** at cleanup. If the world is not loaded yet after a restart, the durable cleanup record remains for the next retry. Staging records live under `plugins/LumaGuilds/frontier-raidclaims`, never in permanent claim/partition records.
- Permanent territories require **four empty chunks (64 blocks)** between unrelated owners, including diagonal corners; the temporary `/raidclaim` position is the sole buffer exception. Existing live Frontier installations with `frontier.claims.buffer_chunks: 1` in their saved `plugins/LumaGuilds/config.yml` must change it to `4` while stopped; plugin defaults do not overwrite an existing config.
- Enemy mining in the raid claim uses the existing 35% speed modifier. Spawners and guild Founding Vaults cannot be placed there. Defenders can counterattack the physical banner; raiders retain the existing once-per-24-hour war-banner cooldown.
- Isolated acceptance: (1) edge vs diagonal and same-owner/solo neighbors; (2) mixed-guild border requiring an explicit target; (3) timed confirmation/grace; (4) outsider mining and banner-only placement; (5) 20-point banner destruction and TNT; (6) normal expiry and raid-window end; (7) restart before/after banner binding; (8) guild disband/vault defeat and physical banner disappearance; (9) out-of-window and world-border failures; (10) ensure SMP behavior is unchanged.

**Do not deploy from this branch yet.** A Java 25/Gradle 9.1 build, automated tests, and live two-guild acceptance are required. Any concurrent change to normal claim availability during a raid must also reject permanent claims on active temporary raid-claim chunks.

## Current rules: test.10 (supersedes earlier claim/pickup instructions)

- `/claim [name]` previews a chunk; `/claim confirm` establishes a Founding Vault (guild chest or solo barrel). Further claims expand edge-connected land from that founding chunk; no additional bells or vaults are placed. Ordinary bells are decorative.
- All chunks owned by a guild or solo owner share one territory name. `/claim rename <name>` updates it everywhere. Entry titles show that name with the guild/owner beneath it.
- Packing requires `/vaultpickup`, a prominent all-land warning, a three-second reading delay, then `/vaultpickup confirm-unclaim-all` within 30 seconds. It surrenders ALL owned claims before removing the vault. Stored contents are retained. Packing is prohibited during raids and does not start a replacement cooldown. Only hostile raid defeat starts the 30-minute cooldown. Guilds with land cannot disband to bypass the objective.
- Raid objectives have 20 damage points, with a two-second accepted-hit interval. Exposed TNT explosions deal four points. Outsider mining inside claims runs at 35% normal speed during raids. Wither explosions, skulls, and direct Wither block destruction cannot damage claimed terrain or objectives; the mob itself remains available.
- Four empty chunks separate unrelated owners (except temporary raid claims). Claims are overworld-only. Overworld/Nether borders are 5000 blocks, Nether travel is 1:1 and the roof is blocked. End border is 512 blocks and gateways are disabled. Spawn reservation is configurable and currently zero.
- Migration unifies legacy claim records/names, replaces the primary solo bell with a barrel, removes obsolete bells, and releases old guild land whose vault was already packed. Existing disconnected land is retained but cannot be used to extend away from the founding territory. The founding record supplies territory permissions.
- Automated validation: 201 selected Frontier/claim/partition/vault tests passed, zero failures or skips. Two-player testing remains on hold; mining feel, particles, UI and live combat still need player acceptance.
- Pre-update world and plugins backup: `archive-2026-11-10T062742Z.tar.gz` on Bloom.


This is a private acceptance-test build, not a production release. Never copy an SMP database or world into this directory. Keep all five plugins together: LumaGuilds, RoseChat, EnthusiaFrontier, TAB, and Vault (API bridge only; no economy provider). Paper is pinned to 26.3 build 246; use Java 25. The supplied Paper SHA-256 is `d158542561e440c4026be7ccb32f551ee88313864792f38ea0ce5d1b88f1b27c`.

The plugin binaries retain their 26.2 compile/API baseline. The automated test results describe those plugin builds, not a real 26.3 server boot; the acceptance checklist must be run on 26.3.

## Start

The package also includes the supplied **Leaf 26.3 build 22** as `leaf-26.3-22.jar` (SHA-256 `e0257b2491efe85c779138fe0c39e1d8e0863e99711d7d050ea18a087f55d8e0`). To select it, add `-Runtime Leaf` to the PowerShell launch command. Paper remains the default; `-Runtime Paper` selects it explicitly. On Linux or a panel, use `java -Xms1G -Xmx4G -jar leaf-26.3-22.jar --nogui` for Leaf. Run only one runtime at a time. Both use the same test world and configuration; use separate copies of the folder for independent comparisons. Leaf's embedded metadata identifies Minecraft 26.3 and Java 25; its startup was verified on the separate Bloom test server.

Read the Minecraft EULA at https://aka.ms/MinecraftEULA. If you agree, run `./Start-FrontierTest.ps1 -Java 'C:/Program Files/Eclipse Adoptium/jdk-25.0.5.8-hotspot/bin/java.exe' -AcceptEula`. Later starts do not need the flag. The script opens no background windows and runs the server in the current terminal. First startup needs internet for Paper and plugin libraries.

The default bind is **127.0.0.1:25566**, whitelisted, online mode, 12 players. From the console use `whitelist add NAME` for each tester. For a remote test host, change `server-ip` to the intended interface and permit port 25566; keep the whitelist enabled. No RCON or query port is enabled. Stop with `stop` before editing configuration or copying files.

On Linux or a hosting panel, upload the contents of this directory into a new server, select Java 25, accept the EULA yourself, and use `java -Xms1G -Xmx4G -jar paper.jar --nogui`. Set the allocated port in `server.properties` and clear `server-ip` if the panel requires all interfaces. Do not copy the SMP plugin directory. The supplied `permissions.yml` grants ordinary testers bell claiming; it does not grant administrative overrides.

The world is `frontier`, with a 5000-block square border centered on 0,0. New players and respawns without beds scatter 80–160 blocks from center; the square within 192 blocks is excluded from claims. Nether and End access are disabled in this first test profile. EnthusiaFrontier tracks generation with **destructive cleanup disabled**. Do not enable cleanup until claim/activity retention has its own acceptance test.

## Historical initial rules (superseded by current rules above)

- `/raidinfo`: current raid timing, claim limits, and your primary solo anchor coordinates. EnthusiaFrontier owns `/frontier`; these commands do not conflict.
- Use `/claim [name]` on solid ground to preview a full chunk, then `/claim confirm`. Claim management remains available through the bell for permissions and guild conversion. Some legacy UI labels still use block counts: 256 blocks means one chunk column.
- `/g create NAME`, invite/join/kick/leave, guild chat, and `/g getvault` are retained. SMP guild menu/home/bank/quest/war commands are blocked in this profile. XP awards are disabled. This is a restricted testing surface, not deletion of every legacy service.
- Retrieve the vault using `/g getvault`, place it inside a guild member's personal claim, then convert that claim through the bell menu. Conversion requires an available vault inside that claim or existing guild land. This prevents creating guild land without a raid objective.
- Personal capacity: 4 chunks. Guild capacity: 16 chunks. Oldest personal claim bell is the solo raid objective. Other bells cannot be used to destroy the owner’s land.
- Daily raid: 20:00–21:00 America/New_York. Objective damage: 20 accepted break attempts, at least 2 seconds apart. Replacement cooldown: 30 minutes after cleanup completes. Vault contents are preserved, not dropped as raid loot.
- End crystals, respawn anchors, elytra flight, and maces are disabled by built-in fallback rules. Totems remain allowed. MaceGuard is not required for this test package and is not bundled.

For fast testing, while stopped edit `plugins/LumaGuilds/config.yml`: set `frontier.raid.timezone: UTC`, `start` to a time one minute before current UTC, `duration_minutes: 15`, `siege.required_hits: 3`, `hit_interval_millis: 1000`, and `replacement_cooldown_minutes: 1`. Restart. Repeat outside-window checks with a future start. Restore normal values for balance tests; no reload is supported.

## Acceptance checklist (two or more real players)

1. Verify all four plugins enable without errors. `/raidinfo` responds. Border is 5000 centered at 0,0; first joins and bedless respawns land safely away from 0,0. No claims can cross spawn or the world border, including negative chunk coordinates.
2. Create personal claims for two players and a guild claim. Verify separate capacity pools, overlap rejection, and full-height chunk protection. Confirm nonmembers cannot build/access containers outside the raid window; during it they can.
3. Try every banned item using both hands, crafting, creative inventory, dispensers, and existing entities. Verify no elytra glide, crystal/anchor placement or activation, or mace damage; ordinary weapons and totems still work.
4. Observe outsider entry and raid-attempt alerts. Try rapid objective hits and owner hits. Only timed outsider hits during the window count. Test vaults and solo anchors independently.
5. Destroy a solo anchor: only that player's personal claims fall. Destroy a guild vault: only guild land falls. Confirm vault contents return exactly once after replacement; compare every stack and quantity.
6. During damage, attempt movement, conversion, transfers, and guild disbanding. After destruction, attempt immediate replacement. Wait for the configured cooldown and verify rebuilding works. Never test only as OP: permission overrides can hide normal-player issues.
7. Stop/restart after partial damage and during cleanup. Check retained damage within the same window, reset in the next, automatic cleanup recovery, and no deletion of unrelated/newly acquired land. Inspect `frontier-sieges`, `frontier-releases`, and logs.
8. Try TNT, block explosions, fire, pistons, indirect bell-support removal, and water against both objectives. None may skip staged damage. Confirm all legacy SMP features stay absent from the ordinary player path.

## Remaining production gates

Real-player acceptance above has not been performed. The chunk-map/UI replacement, recent raid-history UI, complete removal of legacy bank/quest/menu entry points, and definitive PvP/fire/TNT policy still need completion before public launch. Test the core loop now; do not treat this package as a finished public gamemode. Back up the entire directory while stopped to retain SQLite and siege journals together.

## Bloom deployment — November 9, 2026

Server: Ruined Frontier, 208.92.232.97:25566 (panel 942c6700).
Running Leaf 26.3-22-ver/26.3@3ab8807 with Java 25 and a 3500 MB heap within the 5 GB allocation. Final observed memory approximately 4.2 GB.
LumaGuilds 3.0.0-frontier-test.3, RoseChat RC-4, EnthusiaFrontier 0.1.2, and Vault 1.7.3 all enabled. Final startup completed in 4.843 seconds with no ERROR entries.
Corrected optional PlaceholderAPI registration, absent Nexo listener registration, and absent PacketEvents icon adapter startup.
Console smoke checks verified all four plugins green, border width 5000, and raid schedule America/New_York. Whitelist is enabled and currently empty; add testers using `whitelist add NAME` in the panel console.
Original server preserved in root archive `archive-2026-11-10T032456Z.tar.gz` (220.65 MB). The offsite backup attempt failed; this archive is the verified rollback copy.
Remaining nonfatal warnings: RoseChat development build; deprecated PlayerSpawnLocationEvent; schema checker expects legacy claim_permissions/player_access names while repositories create claim_default_permissions/claim_player_permissions. Player raid, vault, solo-anchor, respawn and replacement acceptance checks remain outstanding.
Leaf generated a Nether dimension despite the legacy allow-nether=false property. Do not treat the earlier profile description as verified Nether access prevention on 26.3; dimension access needs a separate compatibility check.
No production SMP files or data were deployed or modified.

## Frontier menu and presentation repair — test.4

The command gate now opens a dedicated vanilla Frontier inventory for /guild, /g, /guild menu, /g help, and onboarding links. It exposes guild information, physical vault retrieval, claim/raid guidance, chat and clickable command suggestions. Ordinary members can use the menu; action permissions remain enforced by the underlying commands. It does not expose remote vault access or the SMP progression dashboard. First-argument completion lists the available Frontier commands.
RoseChat channels.yml now includes the LumaGuilds guild, ally and moderator providers, with ordinary guild-channel permissions. Provider recipient filtering remains responsible for guild/moderator privacy.
The Frontier presentation script produces an isolated locale with crimson #C0261D, gold, cream, copper and rose accents while preserving wording and placeholders. Alerts and the menu use the same palette. Raid alarms are two bells four ticks apart with a restrained bass-drum accent; menu clicks use harp, with no guitar. These are vanilla in-game cues inspired by the shared reference, not a deployment of its entire custom resource-pack sound catalog. Countdown uses hours/minutes/seconds.
Validation: 36 Frontier tests passed, including menu/onboarding aliases and denied menu permissions. Native menu appearance, command suggestions, guild-chat privacy with two guilds and sound playback require client acceptance.
Rollback archive before test.4: archive-2026-11-10T035231Z.tar.gz (plugins and permissions.yml).

## Expanded presentation — test.5

The Frontier profile now applies the reference palette to hardcoded claim feedback, guild command output, claim management labels/lore, menu titles and RoseChat locale/private-message placeholders. Bracketed headings use subtle gold or crimson gradients. Generic GUI rendering and legacy sound adapters are gated by Frontier mode; tests confirm SMP components remain untouched and custom hex colors/click actions/text are retained. Frontier menus bypass resource-pack background glyphs.
Sound vocabulary: quiet harp navigation/private-message cue; harp and chime success; low bass denial; restrained drum on accepted objective hits; double bell for raid alarms; low bell/drum for defenders when their claims fall. There are no guitar samples. Gameplay sounds such as pistons, tools and combat are not globally rewritten. The full custom sound pack and SMP-only war/progression screens are not installed in this gamemode.
The main Frontier inventory has slim gold trim, crimson endpoints and non-italic gradient labels. Claim loss explicitly notifies online owners/guild members. A notification is emitted only after objective defeat succeeds, and hit sounds only after an accepted siege hit.
Validation: 53 focused Frontier, GUI and locale tests pass. Backup before rollout: plugins-2026-11-10T042011Z.tar.gz (41.48 MB). Client visual/audio acceptance remains necessary; no claim of exact browser-mockup audio equivalence is made.
Live verification: Bloom loaded LumaGuilds 3.0.0-frontier-test.5 and all three guild channels; startup finished in 4.534 seconds without ERROR/Exception entries. Console RoseChat help and raidinfo executed successfully.

### Frontier test.6 — raids, storage, and menu (2026-11-10 UTC)

- 126 automated Frontier/Vault tests passed, zero failed or skipped. Includes full 20-hit guild/solo raids, claim-owner isolation, persistent restart recovery, active-siege replacement denial, and the exact 30-minute cooldown boundary.
- Frontier vault capacity is 54 slots at every guild level. Slot 0 is normal storage with deposit/withdraw permissions, persistence, and no currency button; raw gold remains an ordinary vanilla item. Frontier disables physical-currency mode and Chapter Two currency costs. SMP defaults remain enabled where previously configured.
- Forced vault removal captures the current shared inventory before close handlers skip sync. Removal flags clear even on exceptions. Raid destruction continues to preserve stored items for replacement.
- Disband cleanup includes the newly usable first slot. Both SMP reserved-slot and Frontier full-storage behavior are covered by regression tests.
- Guild menu uses four rows: guild information/chat/commands, then vault and claim controls, then close. Vault item instructions now describe 54 slots and raid vulnerability rather than level upgrades and SMP bank menus.
- Local and Bloom deployment packages updated. The incremental update contains only plugins/LumaGuilds.jar; existing data and configuration are retained.
- Automated tests use simulated Bukkit events/repositories plus file-backed raid journals; this is not a two-player in-game raid acceptance test. Remaining manual checks: actual menu appearance, all-slot click/shift-click interactions, attacker/defender feedback, and physical replacement after cooldown.
- Rollback archive before update: plugins-2026-11-10T044528Z.tar.gz on the Bloom test server.

### Frontier test.7 — startup restoration follow-up

Live test.6 boot showed the existing Enthusiast vault was skipped because STARTUP plugins enable before world loading. Frontier now defers vault/hologram restoration to the server thread after startup and completes pending siege/release journals first. Failure retries every 10 seconds rather than restoring a pending defeated objective. The same 126 Frontier/Vault regression tests passed with zero failures/skips; the final distributed package is test.7.

Bloom verification for test.7: Leaf 26.3-22 enabled LumaGuilds test.7 at 04:52:15 UTC; deferred restoration processed 1 intact vault and recreated 1 hologram at 04:52:16. Startup completed in 4.893 seconds. No ERROR/Exception lines in the inspected boot log. /raidinfo responded successfully at 04:52:54 with the America/New_York schedule. Existing development-build/deprecated-event/schema-check warnings remain; no live two-player raid was claimed. Screenshot: ../frontier-test7-console.jpg.

### Frontier test.8 — claim experience, pickup, quests, and TAB (2026-11-10)

- Player testing is on hold at the user's request. No Minecraft clients were controlled for this update.
- Guild quests are disabled in Frontier even if an old progression.yml enables them. Quest tracking/coordinator and queued completion login delivery are disabled; availability reminders also see quests disabled. SMP defaults are unchanged.
- Placing a bell now cancels the vanilla placement and opens a 30-second copper particle chunk preview. Click the chat confirmation or use /claimconfirm while holding a bell within eight blocks of the proposed anchor. /claimcancel dismisses it. Confirmation rechecks overlap, permissions, limits, raid cooldown, world border, and the block before consuming the bell. Original bell orientation is retained. Existing unclaimed bells can be previewed with sneak-right-click.
- /claimoutline (also in /guild) shows nearby owned personal and guild borders for 30 seconds using gold particles. Rendering is per-viewer, bounded, and does not load chunks. Ownership is queried again while drawing, so lost land stops displaying.
- Historical test.8 behavior (superseded by test.9 below): /vaultpickup or sneak-right-click a guild vault presented a destructive-in-game confirmation. /vaultpickup confirm requires BREAK_VAULT rank permission and the same nearby vault. Pickup is unavailable during the raid window or an existing cooldown. It preserves stored items, releases the guild's claims through the durable siege journal, returns the vault item, and starts the configured 30-minute replacement cooldown. This avoids land remaining protected without an objective. Failed cleanup remains locked for recovery.
- frontier.claims.reserved_spawn_radius: 0 disables spawn reservation; a positive value reserves an inclusive square radius around 0,0. Default is 0. Whole chunks must still fit inside the 5k world border, and claiming remains in the Frontier overworld. EnthusiaFrontier's terrain cleanup remains disabled.
- frontier.claims.entry_titles: true shows owner/guild and claim name on claim entry. Implemented directly, without Zone Titles.
- Installed TAB 6.2.1 from https://github.com/NEZNAMY/TAB/releases/tag/6.2.1; SHA256 0B6A21AF6FD3B113C05821DAAED00FF92234A84CA94B29B3AEC0EF1BD313B8A9. Crimson/gold header/footer, online count, ping, command hints, and the current 8–9 PM America/New_York raid schedule. TAB scoreboard teams are disabled to avoid overriding guild nametags. If raid times change, update the TAB footer as well.
- Validation: 201 selected Frontier/Quest/ProgressionConfig tests, 172 passed, 29 skipped, zero failed. The new claim preview tests, quest-disable regression, configurable reservation test, and voluntary pickup cooldown test all passed (not skipped).
- Rollback before upload: plugins-2026-11-10T051505Z.tar.gz on Bloom. The incremental package contains the new LumaGuilds jar, minimally edited live LumaGuilds config, TAB jar, and TAB configuration; no player/guild/world data.

Live verification: test.8 and TAB 6.2.1 enabled on Leaf 26.3-22 at 05:18 UTC (00:18 America/New_York). TAB selected its compatible Paper NMS provider and enabled in 77ms. Boot completed in 4.688s, restored the existing vault and hologram, and had no ERROR/Exception lines in the inspected log. No weekly quest coordinator startup was logged. At 05:19 UTC, claimconfirm/claimoutline/vaultpickup reached their player-only executors from console; plugins listed all five plugins enabled. Screenshot: ../frontier-test8-console.jpg. This verifies startup and registration, not a player UI/raid playtest.

## Test.9: relocation, named land, spacing, and dimensions

- `/vaultpickup` then `/vaultpickup confirm` packs the nearby guild vault outside raids. Claims and all stored contents stay intact; pickup does not create a siege record or start a replacement cooldown. `/guild getvault` can recover the item if it is lost. Vault placement/relocation is blocked throughout the raid window. Only hostile raid destruction triggers the existing claim-loss and 30-minute replacement flow. Guild claims remain while the vault is packed.
- `/claim [name]` previews the current chunk and its copper border without a stick or bell item. Stand on solid ground with air at your feet. `/claim confirm` or `/claimconfirm` revalidates and creates the bell anchor; `/claim cancel` cancels. Manual bell previews remain supported. Names may contain spaces and have 1–50 characters.
- `/claim rename <name>` renames the claim underfoot if the solo owner or a guild member with MANAGE_CLAIMS. Claim entry shows the claim name as the title, with its guild or solo owner in the smaller subtitle. `/claim outline` and `/claimoutline` show nearby owned borders for 30 seconds.
- Historical test.9 used `frontier.claims.buffer_chunks: 1`; the current raid-claim branch raises this to **4**. Same-owner expansion remains possible. Creation, partition editing, and personal ownership transfers enforce spacing. Existing layouts are not deleted or migrated.
- Claims and guild vaults are restricted to the configured NORMAL overworld. Anchor movement cannot move a claim into a different dimension or outside its own land.
- Overworld and Nether borders are 5,000×5,000. Nether portal searches use the original X/Z coordinates in both directions (near-border destinations are clamped inward; normal portal placement may shift slightly). Roof access at Y >= 128 is blocked for movement/teleports, construction and portal creation, with periodic evacuation of already-roofed/mounted players.
- `frontier.world.end_border_size: 512` confines the End to ±256 around the main island. End Gateway travel is cancelled for players/entities, and gateway blocks are removed from loaded End chunks without loading more chunks. End return portals remain usable.
- TAB's ping footer and numeric player-list objective are disabled. Normal Minecraft connection bars are unaffected.
- 191 selected Frontier, claim, partition and vault tests passed; zero failures or skips. Player testing remains on hold.
- Build SHA-256: A26A2BA116CD36F0B9E90308A085FFD78591B4C1E23B6AEA652E4CBE21923C1B.
- Pre-update Bloom rollback archive: `archive-2026-11-10T054050Z.tar.gz` (plugins, bukkit.yml, server.properties; created while stopped).
- Live Bloom verification at 05:45 UTC: Leaf 26.3-22/test.9 started in 5.150s, no ERROR/Exception entries, existing vault and hologram restored intact. Console confirmed Overworld 5000, Nether 5000, End 512. Installed TAB config confirmed no ping footer and playerlist-objective disabled. Existing RoseChat development, missing-table diagnostic, and deprecated spawn-event warnings remain.

Live test.10 verification: Bloom started Leaf 26.3-22 with LumaGuilds test.10 at 06:29 UTC on 2026-11-10 in 5.097 seconds. The existing vault was found intact and Founding Vault migration completed. The inspected startup log contained no ERROR or Exception entries. Console version and raidinfo confirmed test.10, 20 objective damage points, TNT damage 4, raider mining 35%, and Wither raiding disabled. No player tests were performed.

## Test.11: one home per territory and older Java clients

- One shared guild home: `/guild sethome` and `/guild home` (also `/setguildhome`, `/guildhome`, `/ghome`). Guild MANAGE_CLAIMS rank permission is required to set it; current members can use it. One personal home: `/sethome` and `/home` (alias `/claimhome`), inside that player's personal claims. Guild members do not receive additional personal slots.
- Setting replaces the single destination. Homes persist in frontier-homes.yml and are tied to the founding claim UUID, so surrendered/defeated land and fresh replacement foundations cannot reactivate an old home. Ownership, membership, world, border and safe destination are checked before arrival.
- Five-second warmup; movement, damage, external teleport, leaving the guild, losing land, blocked destinations, and raid start cancel it. Homes cannot be set/used during the raid window or combat. Built-in 15-second damage/attack lock works without CombatLogX; when installed, its combat flag is also respected. Successful teleports have a 30-second cooldown.
- ViaVersion 5.12.2 and ViaBackwards 5.12.2 are bundled from their official GitHub releases. ViaVersion blocks clients below 1.21. Supported 1.21.x through 26.3 Java clients can use protocol translation; this does not promise support for unknown future releases or snapshots. Older clients see replacement visuals for new content. Native 26.3 remains the most faithful experience. No Bedrock support is added here.
- 208 selected Frontier/claim/partition/vault tests passed, zero failures/skips, including seven new home tests. Client login and two-player raid acceptance remain on hold.
- Pre-update plugin rollback archive: plugins-2026-11-10T065453Z.tar.gz.
- Biome-focused 26.3 and 26.4-snapshot-7 packs and sampled candidate seeds live in ../frontier-worldgen. They are separate fresh-world candidates, not an overwrite of the existing Bloom test map. Paper is an acceptable alternative to Leaf once a compatible 26.4 build exists. Never downgrade a 26.4 world into 26.3.

Live test.11 verification (2026-11-10 01:58 America/New_York): Leaf 26.3-22 started in 5.465s; LumaGuilds test.11, ViaVersion 5.12.2 and ViaBackwards 5.12.2 enabled. ViaVersion detected server protocol 777 (26.3), loaded mapping data, and the existing guild vault restored intact. No ERROR/Exception entries in the inspected startup log. Console confirmed installed versions and player-only home/guildhome executors.

ViaVersion emitted a security warning for the vanilla 1.21–1.21.4 protocols, recommending blocking them if unnecessary. These remain allowed to meet the requested full 1.21.x range; this warning was reported to the user. Successful startup is not an actual older-client login test.
