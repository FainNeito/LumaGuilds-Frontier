package net.lumalyte.lg.interaction.listeners

import net.kyori.adventure.text.Component
import net.lumalyte.lg.application.actions.claim.GetClaimAtPosition
import net.lumalyte.lg.application.results.claim.GetClaimAtPositionResult
import net.lumalyte.lg.application.services.GuildService
import net.lumalyte.lg.config.FrontierConfig
import net.lumalyte.lg.domain.entities.Claim
import net.lumalyte.lg.domain.values.Position2D
import org.bukkit.Location
import org.bukkit.block.Container
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.plugin.java.JavaPlugin
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Observes attempted hostile actions even when another protection cancels them. */
class FrontierAlertListener(
    private val plugin: JavaPlugin,
    private val config: FrontierConfig,
    private val claims: GetClaimAtPosition,
    private val guilds: GuildService,
) : Listener {
    var raidNotifications: FrontierRaidNotifications? = null
    private data class AlertKey(val claim: UUID, val actor: UUID, val kind: String)
    private val recentAlerts = mutableMapOf<AlertKey, Instant>()
    private var previousActive: Boolean? = null
    private var previousRemaining: Long? = null

    fun start() {
        FrontierFeedback.initialize(plugin)
        registerTabCountdown()
        plugin.server.scheduler.runTaskTimer(plugin, Runnable { tick() }, 1L, 20L)
    }

    private fun tick() {
        val now = Instant.now()
        val state = config.schedule.stateAt(now)
        val seconds = Duration.between(now, state.nextTransition).seconds.coerceAtLeast(0)
        if (previousActive != null && previousActive != state.active) {
            plugin.server.broadcast(FrontierTheme.message("Raid", if (state.active)
                "The raid window is open. Claims are vulnerable."
                else "The raid window has closed. Claim protection is restored.", state.active))
            plugin.server.onlinePlayers.forEach {
                FrontierFeedback.show(it, if (state.active) "raid-open" else "raid-close",
                    if (state.active) "RAIDS HAVE BEGUN" else "CLAIMS PROTECTED",
                    if (state.active) "Defend your Founding Vault" else "The raid window has ended",
                    danger = state.active, important = true)
            }
        } else if (previousActive == state.active) {
            for (warning in listOf(900L, 300L, 60L)) {
                if ((previousRemaining ?: 0) > warning && seconds <= warning) {
                    plugin.server.broadcast(FrontierTheme.message("Raid", "Raid window ${if (state.active) "closes" else "opens"} in ${warning / 60} minute(s).", true))
                    plugin.server.onlinePlayers.forEach { FrontierFeedback.show(it, "raid-warning", "", "", title = false) }
                }
            }
        }
        previousActive = state.active
        previousRemaining = seconds
        recentAlerts.entries.removeIf { Duration.between(it.value, now).seconds >= 30 }
    }

    internal fun countdown(now: Instant = Instant.now()): String {
        val state = config.schedule.stateAt(now)
        val seconds = (Duration.between(now, state.nextTransition).toMillis() + 999) / 1000
        return "${if (state.active) "CLAIMS RAIDABLE - closes in" else "Claims raidable in"} ${FrontierTheme.duration(seconds)}"
    }

    private fun registerTabCountdown() {
        val tab = plugin.server.pluginManager.getPlugin("TAB") ?: return
        if (!tab.isEnabled) return
        runCatching {
            val loader = tab.javaClass.classLoader
            val api = Class.forName("me.neznamy.tab.api.TabAPI", true, loader)
            val manager = api.getMethod("getPlaceholderManager").invoke(api.getMethod("getInstance").invoke(null))
            Class.forName("me.neznamy.tab.api.placeholder.PlaceholderManager", true, loader)
                .getMethod("registerServerPlaceholder", String::class.java, Int::class.javaPrimitiveType, java.util.function.Supplier::class.java)
                .invoke(manager, "%frontier_raid_countdown%", 1000, java.util.function.Supplier<String> { countdown() })
            plugin.logger.info("Registered Frontier raid countdown for TAB")
        }.onFailure { plugin.logger.warning("Could not register TAB raid countdown: ${it.message}") }
    }

    @EventHandler fun onPluginEnable(event: org.bukkit.event.server.PluginEnableEvent) {
        if (event.plugin.name == "TAB") registerTabCountdown()
    }

    @EventHandler fun onJoin(event: org.bukkit.event.player.PlayerJoinEvent) {
        val now = Instant.now()
        val state = config.schedule.stateAt(now)
        val time = java.time.format.DateTimeFormatter.ofPattern("HH:mm z").withZone(config.schedule.zone)
            .format(state.nextTransition)
        event.player.sendMessage(FrontierTheme.message("Raid", "${countdown(now)}. ${if (state.active) "Protection returns" else "Raids start"} at $time (${config.schedule.zone.id}).", state.active))
    }

    private fun publicTerritoryAt(location: Location) = config.serverTerritory?.takeIf {
        location.world.name == config.worldName && it.contains(location.blockX,location.blockZ)
    }

    private fun claimAt(location: Location): Claim? =
        (claims.execute(location.world.uid, Position2D(location.blockX, location.blockZ))
            as? GetClaimAtPositionResult.Success)?.claim

    private fun member(player: Player, claim: Claim): Boolean = claim.teamId?.let { team ->
        guilds.getPlayerGuilds(player.uniqueId).any { it.id == team }
    } ?: (claim.playerId == player.uniqueId)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val to = event.to ?: return
        val from = event.from
        if (from.world.uid == to.world.uid && (from.blockX shr 4) == (to.blockX shr 4) &&
            (from.blockZ shr 4) == (to.blockZ shr 4)) return
        val claim = claimAt(to)
        val publicTerritory = publicTerritoryAt(to)
        val previous = claimAt(from)
        val sameOwner = previous != null && claim != null && previous.teamId == claim.teamId &&
            (claim.teamId != null || previous.playerId == claim.playerId)
        if (previous != null && !sameOwner)
            event.player.sendMessage(FrontierTheme.message("Claim", "Leaving ${previous.name}."))
        val previousPublic = publicTerritoryAt(from)
        if (previousPublic != null && publicTerritory == null)
            event.player.sendMessage(FrontierTheme.message("Territory", "Leaving ${previousPublic.name}."))
        if (claim == null && publicTerritory != null) {
            if (publicTerritoryAt(from) == null) {
                FrontierFeedback.show(event.player,"claim-enter",publicTerritory.name,"Server territory · Open to everyone",title=config.claimTitles)
                event.player.sendMessage(FrontierTheme.message("Territory", "Entering ${publicTerritory.name}. Building and PvP allowed; claiming disabled."))
            }
            return
        }
        if (claim == null) return
        if (sameOwner) return
        run {
            val owner = claim.teamId?.let { guilds.getGuild(it)?.name }
                ?: (org.bukkit.Bukkit.getOfflinePlayer(claim.playerId).name ?: "Solo claim")
            FrontierFeedback.show(event.player, "claim-enter", claim.name,
                if (claim.teamId != null) "Guild · $owner" else "Claim of $owner", title = config.claimTitles)
        }
        if (member(event.player, claim)) return
        event.player.sendMessage(FrontierTheme.message("Claim", "Entering ${claim.name}."))
        alert(claim, event.player, to, "entered")
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) = onMove(event)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onBreak(event: BlockBreakEvent) = attempt(event.player, event.block.location, "attempted block damage")

    @EventHandler(priority = EventPriority.MONITOR)
    fun onContainer(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (block.state is Container) attempt(event.player, block.location, "attempted container access")
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onAttack(event: EntityDamageByEntityEvent) {
        val defender = event.entity as? Player ?: return
        val attacker = event.damager as? Player ?: (event.damager as? Projectile)?.shooter as? Player ?: return
        val claim = claimAt(defender.location) ?: return
        if (member(defender, claim)) attempt(attacker, defender.location, "attacked a defender")
    }

    private fun attempt(player: Player, location: Location, action: String) {
        if (!config.schedule.stateAt(Instant.now()).active) return
        val claim = claimAt(location) ?: return
        if (!member(player, claim)) alert(claim, player, location, action)
    }

    private fun alert(claim: Claim, actor: Player, location: Location, kind: String) {
        val now = Instant.now()
        val key = AlertKey(claim.teamId ?: claim.playerId, actor.uniqueId, if (kind == "entered") "entry" else "raid")
        if (recentAlerts[key]?.let { Duration.between(it, now).seconds < 30 } == true) return
        recentAlerts[key] = now
        val message = "[Claim alert] ${actor.name} $kind at ${claim.name}, chunk ${location.blockX shr 4}, ${location.blockZ shr 4} (${location.world.name})."
        if (kind != "entered") raidNotifications?.notify(net.lumalyte.lg.application.services.FrontierReplacementPolicy.ownerOf(claim), "raid", message)
        plugin.server.onlinePlayers.filter { member(it, claim) }.forEach {
            it.sendMessage(FrontierTheme.message(if (kind == "entered") "Claim" else "Raid", message.substringAfter("] "), kind != "entered"))
            FrontierFeedback.show(it, if (kind == "entered") "visitor" else "raid",
                if (kind == "entered") "VISITOR IN YOUR CLAIM" else "CLAIM UNDER ATTACK",
                "${actor.name} · ${claim.name}", danger = kind != "entered", title = kind != "entered", important = kind != "entered")
        }
        plugin.logger.info(message)
    }
}
