# LumaGuilds Frontier

The independent Frontier game-mode variant of [LumaGuilds](https://github.com/BadgersMC/LumaGuilds), maintained separately from the SMP plugin.

## Gameplay

- Chunk-based guild and solo territories founded by a vault, with previews, a claim buffer, outlines, entry titles, and a shared territory name.
- A daily raid window, durable objectives, TNT-assisted sieges, claim loss on raid defeat, and replacement cooldowns.
- Explicit confirmation before packing a vault and surrendering its territory; packing cannot bypass an active raid.
- Guild homes, solo homes, raid-window war banners, and a compact guild menu.
- Configurable dimension/spawn restrictions and a stripped-back Frontier profile.

See [current rules](docs/ANARCHY_FRONTIER_MODE.md) and [testing instructions](docs/FRONTIER_TESTING.md). Older design notes in those documents are historical; the current-rules section takes precedence.

## Status and isolation

Development/test software. This repository contains the current Frontier source, not a production deployment bundle. Snapshot-8 Paper and FrontierBridge compatibility work is separate and is not yet deployment-ready. No Minecraft server jars, worlds, databases, accounts, or live server credentials are included.

The Bukkit plugin remains named `LumaGuilds` for data and integration compatibility. Do not install both variants on one server. Use a separate server directory, configuration, and database from SMP. Frontier is enabled through its explicit configuration profile; ordinary upstream defaults are retained to avoid silently migrating existing data.

## Build

Use JDK 25 and the checked-in Gradle wrapper. The build workflows compile pinned RoseChat, Playtime, CombatLogX, and EnthusiaStaff API dependencies before running `./gradlew test shadowJar`. For local dependency details see [the dependency manifest](ci/frontier-local-dependencies.json). Built jars are written to `build/libs/`.

Build and unit-test workflows are retained. Upstream publishing and wiki-deployment workflows are archived under `.github/upstream-workflows/` and do not run in this repository.

## Upstream and license

Based on BadgersMC/LumaGuilds at `3d76a62`, with its Git history and author attribution preserved. The upstream project is also known as Bell Claims. The original README is preserved in [docs/UPSTREAM_README.md](docs/UPSTREAM_README.md).

Licensed under [GNU AGPL-3.0](LICENSE), retaining upstream notices. This repository is the source location for the modified Frontier variant.
