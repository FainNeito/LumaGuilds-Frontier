package net.lumalyte.lg.interaction.listeners

import io.papermc.paper.scoreboard.numbers.NumberFormat
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.lumalyte.lg.application.persistence.ClaimRepository
import net.lumalyte.lg.application.persistence.PartitionRepository
import net.lumalyte.lg.application.services.FrontierRaidClaims
import net.lumalyte.lg.application.services.FrontierRaidClaim
import net.lumalyte.lg.application.services.GuildService
import net.lumalyte.lg.application.services.RelationService
import net.lumalyte.lg.config.FrontierConfig
import net.lumalyte.lg.domain.entities.Claim
import net.lumalyte.lg.domain.entities.Guild
import net.lumalyte.lg.domain.entities.RelationType
import net.lumalyte.lg.domain.entities.VaultStatus
import net.lumalyte.lg.domain.values.Area
import net.lumalyte.lg.domain.values.FrontierChunk
import net.lumalyte.lg.domain.values.Position2D
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Scoreboard
import org.bukkit.scoreboard.Team
import java.util.UUID

/** Chunk-oriented, north-up view. Kept independent of Bukkit so orientation is regression-tested. */
internal object FrontierMapBoardPolicy {
    fun canCreate(objectives: Int, teams: Int) = objectives == 0 && teams == 0
}

internal object FrontierMapGrid {
    const val RADIUS = 5
    const val SIZE = RADIUS * 2 + 1

    fun <T> around(x: Int, z: Int, cell: (Int, Int) -> T): List<List<T>> =
        (-RADIUS..RADIUS).map { dz ->
            (-RADIUS..RADIUS).map { dx -> cell(x + dx, z + dz) }
        }

    fun changedChunk(oldWorld: UUID, oldX: Int, oldZ: Int, newWorld: UUID, newX: Int, newZ: Int): Boolean =
        oldWorld != newWorld || Math.floorDiv(oldX, 16) != Math.floorDiv(newX, 16) ||
            Math.floorDiv(oldZ, 16) != Math.floorDiv(newZ, 16)
}

internal enum class MapTile(val color: TextColor) {
    OWN(TextColor.color(0x51D379)),
    ALLY(TextColor.color(0x63A5FF)),
    FOREIGN(TextColor.color(0xE55252)),
    RAID(TextColor.color(0xF5C542)),
    WILDERNESS(TextColor.color(0xB5B9BD)),
    RESTRICTED(TextColor.color(0x4B4144)),
}

internal enum class MapSymbol(val glyph: String) {
    SQUARE("■"), VAULT("▣"), WAR_BANNER("⚑"),
}

internal data class MapCell(val tile: MapTile, val symbol: MapSymbol = MapSymbol.SQUARE) {
    companion object {
        fun claimed(tile: MapTile, hasVault: Boolean) =
            MapCell(tile, if (hasVault) MapSymbol.VAULT else MapSymbol.SQUARE)

        fun raid(claim: FrontierRaidClaim) =
            MapCell(MapTile.RAID, if (claim.bannerId != null) MapSymbol.WAR_BANNER else MapSymbol.SQUARE)
    }
}

internal object FrontierMapVaults {
    fun guildAt(guild: Guild?, chunk: FrontierChunk): Boolean {
        if (guild?.vaultStatus != VaultStatus.AVAILABLE) return false
        val vault = guild.vaultChestLocation ?: return false
        return vault.worldId == chunk.worldId && Math.floorDiv(vault.x, 16) == chunk.x &&
            Math.floorDiv(vault.z, 16) == chunk.z
    }
}

internal object FrontierMapOwnership {
    fun classify(
        owners: Collection<Claim>,
        playerId: UUID,
        guildIds: Set<UUID>,
        allied: (UUID) -> Boolean,
    ): MapTile? {
        if (owners.isEmpty()) return null
        if (owners.any { claim ->
                claim.teamId?.let(guildIds::contains) == true ||
                    claim.teamId == null && claim.playerId == playerId
            }) return MapTile.OWN
        if (owners.any { claim -> claim.teamId?.let(allied) == true }) return MapTile.ALLY
        return MapTile.FOREIGN
    }
}

/** Frontier-only sidebar. Other plugins' existing sidebar objectives take precedence. */
class FrontierTerritoryScoreboard(
    private val plugin: JavaPlugin,
    private val config: FrontierConfig,
    private val claims: ClaimRepository,
    private val partitions: PartitionRepository,
    private val guilds: GuildService,
    private val relations: RelationService,
    private val raidClaims: FrontierRaidClaims,
) : Listener {
    private data class Display(
        val previous: Scoreboard,
        val scoreboard: Scoreboard,
        val objective: Objective,
        val lines: List<Team>,
    )

    private val active = mutableMapOf<UUID, Display>()
    private val preferencesFile = plugin.dataFolder.resolve("frontier-map-preferences.yml")
    private val preferences = org.bukkit.configuration.file.YamlConfiguration().apply { if(preferencesFile.exists()) load(preferencesFile) }

    private val cream = TextColor.color(0xEADBC9)
    private val gold = TextColor.color(0xE9BB67)

    fun start() {
        plugin.getCommand("claimmap")?.setExecutor { sender, _, _, args ->
            if(sender is Player) {
                val key=sender.uniqueId.toString()
                val enabled=when(args.firstOrNull()?.lowercase()) { "on"->true; "off"->false; null->!preferences.getBoolean(key,true); else->null }
                if(enabled==null) sender.sendMessage(FrontierTheme.message("Map","Use /claimmap [on|off]."))
                else try {
                    val next=org.bukkit.configuration.file.YamlConfiguration().apply { loadFromString(preferences.saveToString());set(key,enabled) }
                    val temp=preferencesFile.toPath().resolveSibling(preferencesFile.name+".tmp")
                    java.nio.file.Files.writeString(temp,next.saveToString())
                    java.nio.file.Files.move(temp,preferencesFile.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    preferences.loadFromString(next.saveToString())
                    if(enabled) update(sender) else release(sender)
                    sender.sendMessage(FrontierTheme.message("Map",if(enabled) "Claim map enabled when scoreboard space is available." else "Claim map disabled."))
                } catch(failure:Exception) { plugin.logger.warning("Could not save claim-map preference: ${failure.message}");sender.sendMessage(FrontierTheme.message("Map","Preference could not be saved; previous setting retained.")) }
            }
            true
        }

        // Claim changes, diplomacy and raid-banner expiry can happen without movement.
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            plugin.server.onlinePlayers.forEach(::update)
        }, 2L, 300L)
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            plugin.server.onlinePlayers.forEach(::update)
        }, 2L)
    }

    fun shutdown() {
        for (player in plugin.server.onlinePlayers) release(player)
        active.clear()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (event.player.isOnline) update(event.player)
        }, 2L)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val to = event.to ?: return
        val from = event.from
        if (FrontierMapGrid.changedChunk(from.world.uid, from.blockX, from.blockZ,
                to.world.uid, to.blockX, to.blockZ)) update(event.player, to)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) = update(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) = release(event.player)

    private fun update(player: Player, location: Location = player.location) {
        if (!preferences.getBoolean(player.uniqueId.toString(),true) || !location.world.name.equals(config.worldName, ignoreCase = true)) {
            release(player)
            return
        }
        var display = active[player.uniqueId]
        if (display != null && player.scoreboard !== display.scoreboard) {
            // Another plugin replaced the board; do not compete for ownership.
            release(player)
            display = null
        }
        if (display == null) {
            if (!FrontierMapBoardPolicy.canCreate(player.scoreboard.objectives.size,player.scoreboard.teams.size)) return
            display = create(player) ?: return
        }
        if (display.scoreboard.objectives.any { it !== display.objective } || display.scoreboard.teams.any { it !in display.lines }) {
            release(player); return
        }
        if (display.scoreboard.getObjective(DisplaySlot.SIDEBAR) !== display.objective) return
        render(player, display, location)
    }

    private fun create(player: Player): Display? {
        val board = Bukkit.getScoreboardManager()?.newScoreboard ?: return null
        val objective = board.registerNewObjective("frontier_map", Criteria.DUMMY,
            Component.text("  FRONTIER  ", gold))
        objective.numberFormat(NumberFormat.blank())
        objective.displaySlot = DisplaySlot.SIDEBAR
        val lines = (0 until 15).map { index ->
            val entry = "${ChatColor.COLOR_CHAR}${index.toString(16)}"
            board.registerNewTeam("frontier_$index").also { team ->
                team.addEntry(entry)
                board.getObjective("frontier_map")!!.getScore(entry).score = 15 - index
            }
        }
        return Display(player.scoreboard, board, objective, lines).also {
            active[player.uniqueId] = it
            player.scoreboard = board
        }
    }

    private fun release(player: Player) {
        val display = active.remove(player.uniqueId) ?: return
        val foreign=display.scoreboard.objectives.any { it !== display.objective } || display.scoreboard.teams.any { it !in display.lines }
        if (player.scoreboard === display.scoreboard && !foreign) player.scoreboard = display.previous
        runCatching { display.objective.unregister() }
        display.lines.forEach { runCatching { it.unregister() } }
    }

    private fun render(player: Player, display: Display, location: Location) {
        val center = FrontierChunk.atBlock(location.world.uid, location.blockX, location.blockZ)
        val ownGuilds = guilds.getPlayerGuilds(player.uniqueId).mapTo(mutableSetOf()) { it.id }
        val claimCache = mutableMapOf<UUID, Claim?>()
        val guildCache = mutableMapOf<UUID, Guild?>()
        val allied = mutableMapOf<UUID, Boolean>()
        val now = System.currentTimeMillis()

        fun tile(x: Int, z: Int): MapCell {
            val chunk = FrontierChunk(center.worldId, x, z)
            val permanent = partitions.getByChunk(Position2D(x, z)).mapNotNull { partition ->
                claimCache.getOrPut(partition.claimId) { claims.getById(partition.claimId) }
                    ?.takeIf { it.worldId == chunk.worldId }
            }
            FrontierMapOwnership.classify(permanent, player.uniqueId, ownGuilds) { owner ->
                allied.getOrPut(owner) {
                    ownGuilds.any { relations.getRelationType(it, owner) == RelationType.ALLY }
                }
            }?.let { ownership ->
                val vault = permanent.any { claim ->
                    claim.teamId?.let { owner ->
                        FrontierMapVaults.guildAt(guildCache.getOrPut(owner) { guilds.getGuild(owner) }, chunk)
                    } == true
                }
                return MapCell.claimed(ownership, vault)
            }
            raidClaims.at(chunk, now)?.let { return MapCell.raid(it) }
            val area = Area(Position2D(x * 16, z * 16), Position2D(x * 16 + 15, z * 16 + 15))
            return MapCell(if (config.permitsLand(area)) MapTile.WILDERNESS else MapTile.RESTRICTED)
        }

        val grid = FrontierMapGrid.around(center.x, center.z, ::tile)
        val legendTop = Component.text("■ ", MapTile.OWN.color).append(Component.text("Own  ", cream))
            .append(Component.text("■ ", MapTile.ALLY.color)).append(Component.text("Ally  ", cream))
            .append(Component.text("■ ", MapTile.FOREIGN.color)).append(Component.text("Other", cream))
        val legendBottom = Component.text("■ ", MapTile.RAID.color).append(Component.text("Raid  ", cream))
            .append(Component.text("■ ", MapTile.WILDERNESS.color)).append(Component.text("Wild  ", cream))
            .append(Component.text("◆ ", gold)).append(Component.text("You", cream))
        val legendMarkers = Component.text("▣ ", cream).append(Component.text("Vault  ", cream))
            .append(Component.text("⚑ ", MapTile.RAID.color)).append(Component.text("War banner", cream))
        val rows = listOf(legendTop) + grid.mapIndexed { z, row ->
            row.foldIndexed(Component.empty()) { x, line, cell ->
                val marker = x == FrontierMapGrid.RADIUS && z == FrontierMapGrid.RADIUS
                line.append(Component.text(if (marker) "◆" else cell.symbol.glyph,
                    if (marker) gold else cell.tile.color))
                    .append(if (x == FrontierMapGrid.SIZE - 1) Component.empty() else Component.text(" "))
            }
        } + listOf(legendBottom, legendMarkers, Component.text("Chunk ${center.x}, ${center.z}" +
            when (grid[FrontierMapGrid.RADIUS][FrontierMapGrid.RADIUS].symbol) {
                MapSymbol.VAULT -> " · Vault here"
                MapSymbol.WAR_BANNER -> " · Banner here"
                MapSymbol.SQUARE -> ""
            }, NamedTextColor.GRAY))

        display.lines.zip(rows).forEach { (team, row) -> team.prefix(row) }
    }
}
