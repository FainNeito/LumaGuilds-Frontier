package net.lumalyte.lg.interaction.listeners

import net.lumalyte.lg.application.persistence.ClaimRepository
import net.lumalyte.lg.application.services.*
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.*
import org.bukkit.event.block.*
import org.bukkit.event.player.*
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.logging.Level

/** Server-thread only. A failed state load or durable save disables expiry, never grants it. */
class FrontierClaimDecay(
    private val plugin: JavaPlugin,
    private val settings: YamlConfiguration,
    private val claims: ClaimRepository,
    private val members: MemberService,
    private val release: FrontierClaimRelease,
    private val clock: () -> Long = System::currentTimeMillis,
) : Listener {
    var raidNotifications: FrontierRaidNotifications? = null
    private val file = plugin.dataFolder.resolve("frontier-activity.yml")
    private val state = YamlConfiguration()
    private var healthy = true
    private val warned = mutableMapOf<UUID, String>()
    private val day = 86400000L

    init {
        try { if (file.exists()) state.load(file) }
        catch (failure: Exception) {
            healthy = false
            plugin.logger.log(Level.SEVERE, "Frontier activity state unreadable; claim expiry disabled", failure)
        }
    }

    fun start() {
        if (settings.getBoolean("decay.enabled", false))
            plugin.server.scheduler.runTaskTimer(plugin, Runnable { checkTerritories() }, 20, 1200)
    }

    private fun persist() {
        val temporary = file.toPath().resolveSibling(file.name + ".tmp")
        Files.writeString(temporary, state.saveToString())
        // If the filesystem cannot atomically replace, fail closed rather than risk truncated state.
        Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    internal fun checkTerritories() {
        if (!healthy || !settings.getBoolean("decay.enabled", false)) return
        try {
            val now = clock()
            val timeout = settings.getLong("decay.inactive-days", 14).coerceIn(1, 3650) * day
            val groups = claims.getAll().groupBy(FrontierReplacementPolicy::ownerOf)
            for ((owner, land) in groups) {
                val root = land.minWith(compareBy<net.lumalyte.lg.domain.entities.Claim> { it.creationTime }.thenBy { it.id })
                val path = "territories.${root.id}"
                if (!state.isLong("$path.initialized")) state.set("$path.initialized", now)
                val initialized = state.getLong("$path.initialized")
                val currentMembers = if (owner.type == FrontierOwnerType.GUILD) members.getGuildMembers(owner.id) else emptySet()
                val players = if (owner.type == FrontierOwnerType.PERSONAL) setOf(owner.id) else currentMembers.map { it.playerId }.toSet()
                val lastActivity = maxOf(initialized,
                    players.maxOfOrNull { state.getLong("players.$it", initialized) } ?: initialized,
                    currentMembers.maxOfOrNull { it.joinedAt.toEpochMilli() } ?: initialized)
                val online = players.mapNotNull { plugin.server.getPlayer(it) }.filter { it.isOnline }
                val remaining = timeout - (now - lastActivity).coerceAtLeast(0)
                val threshold = settings.getIntegerList("decay.warning-days").filter { it > 0 && remaining <= it * day }.minOrNull()
                if (threshold != null) for (player in online) {
                    val warning = "${root.id}:$lastActivity:$threshold"
                    if (warned[player.uniqueId] != warning) {
                        player.sendMessage(FrontierTheme.message("Claim", "Your territory has ${((remaining.coerceAtLeast(0) + day - 1) / day)} day(s) before inactivity release. Move or interact to renew it."))
                        warned[player.uniqueId] = warning
                    }
                }
                // Save first observation/activity BEFORE making any release decision durable.
                persist()
                if (threshold != null && state.getString("$path.discord-warning") != "$lastActivity:$threshold") {
                    raidNotifications?.notify(owner, "decay-$threshold", "Your Frontier territory has ${(remaining.coerceAtLeast(0) + day - 1) / day} day(s) before inactivity release. Play on Frontier to renew activity.")
                    state.set("$path.discord-warning", "$lastActivity:$threshold")
                }
                if (FrontierLivingPolicy.expired(lastActivity, initialized, now, timeout, online.isNotEmpty())) {
                    release.release(owner)
                    plugin.logger.info("Released inactive Frontier territory ${root.id} (${owner.type}); builds and vault contents retained")
                }
            }
            // Persist activity even while there are no territories.
            persist()
            warned.keys.retainAll(plugin.server.onlinePlayers.map { it.uniqueId }.toSet())
        } catch (failure: Exception) {
            // A fresh process must successfully reload the durable state before expiry can resume.
            healthy = false
            plugin.logger.log(Level.SEVERE, "Frontier inactivity check failed; expiry disabled until restart", failure)
        }
    }

    private fun active(id: UUID) {
        if (healthy && settings.getBoolean("decay.enabled", false)) state.set("players.$id", clock())
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun move(event: PlayerMoveEvent) {
        val to = event.to
        if (event.from.blockX != to.blockX || event.from.blockY != to.blockY || event.from.blockZ != to.blockZ) active(event.player.uniqueId)
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun breakBlock(event: BlockBreakEvent) = active(event.player.uniqueId)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun place(event: BlockPlaceEvent) = active(event.player.uniqueId)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun interact(event: PlayerInteractEvent) = active(event.player.uniqueId)

    @EventHandler(priority = EventPriority.MONITOR)
    fun join(event: PlayerJoinEvent) { checkTerritories() }

    @EventHandler(priority = EventPriority.MONITOR)
    fun quit(event: PlayerQuitEvent) {
        if (!healthy) return
        try { persist() } catch (failure: Exception) {
            healthy = false
            plugin.logger.log(Level.SEVERE, "Frontier activity save failed; expiry disabled", failure)
        }
    }
}
