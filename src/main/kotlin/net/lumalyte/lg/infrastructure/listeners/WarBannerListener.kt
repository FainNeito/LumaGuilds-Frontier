package net.lumalyte.lg.infrastructure.listeners

import net.badgersmc.nexus.i18n.LangService
import net.lumalyte.lg.application.services.GuildService
import net.lumalyte.lg.application.services.WarBannerPlacementResult
import net.lumalyte.lg.application.services.WarBannerService
import net.lumalyte.lg.common.PluginKeys
import net.lumalyte.lg.domain.entities.WarBannerState
import net.lumalyte.lg.infrastructure.services.WarBannerServiceBukkit
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.block.Banner
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

class WarBannerListener(
    private val core: WarBannerService,
    private val bukkit: WarBannerServiceBukkit,
    private val guildService: GuildService,
    private val lang: LangService,
    private val frontier: net.lumalyte.lg.config.FrontierConfig = net.lumalyte.lg.config.FrontierConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
) : Listener {
    private data class Damage(var hits: Int, var last: Long)
    private val damage = mutableMapOf<UUID, Damage>()

    private fun marked(block: org.bukkit.block.Block): Boolean = (block.state as? Banner)
        ?.persistentDataContainer?.has(PluginKeys.WAR_BANNER_ID, PersistentDataType.STRING) == true

    private fun damageBanner(block: org.bukkit.block.Block, amount: Int): Boolean {
        val banner = block.state as? Banner ?: return false
        val guildId = banner.persistentDataContainer.get(PluginKeys.WAR_BANNER_GUILD_ID, PersistentDataType.STRING)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return true
        val state = core.state(guildId) ?: return true
        val now = clock()
        if (!state.isActiveAt(now)) { bukkit.removePhysical(state); return false }
        if (state.worldId != block.world.uid || state.x != block.x || state.y != block.y || state.z != block.z) return true
        if (!frontier.schedule.stateAt(java.time.Instant.ofEpochMilli(now)).active) return false
        damage.entries.removeIf { now - it.value.last > 900_000 }
        val progress = damage.getOrPut(state.bannerId) { Damage(0, Long.MIN_VALUE / 2) }
        if (amount == 1 && now - progress.last < frontier.hitInterval.toMillis()) return false
        progress.hits += amount
        progress.last = now
        if (progress.hits < frontier.requiredHits) return false
        damage.remove(state.bannerId)
        return true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPhysics(event: org.bukkit.event.block.BlockPhysicsEvent) {
        if (frontier.enabled && marked(event.block)) event.isCancelled = true
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onFlow(event: org.bukkit.event.block.BlockFromToEvent) {
        if (frontier.enabled && marked(event.toBlock)) event.isCancelled = true
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBurn(event: org.bukkit.event.block.BlockBurnEvent) {
        if (frontier.enabled && marked(event.block)) event.isCancelled = true
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityChange(event: org.bukkit.event.entity.EntityChangeBlockEvent) {
        if (frontier.enabled && marked(event.block)) event.isCancelled = true
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPiston(event: org.bukkit.event.block.BlockPistonExtendEvent) {
        if (frontier.enabled && event.blocks.any { marked(it) || marked(it.getRelative(event.direction)) }) event.isCancelled = true
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onRetract(event: org.bukkit.event.block.BlockPistonRetractEvent) {
        if (frontier.enabled && event.blocks.any { marked(it) || marked(it.getRelative(event.direction)) }) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        val guildId = bukkit.itemGuildId(event.itemInHand) ?: return
        val player = event.player
        val guild = guildService.getGuild(guildId)
        if (guild == null) {
            event.isCancelled = true
            player.sendMessage(lang.msg("war_banner.feedback.guild_missing"))
            return
        }

        bukkit.cleanupForPlacement(guildId)
        val block = event.blockPlaced
        if (frontier.enabled && (block.world.environment != org.bukkit.World.Environment.NORMAL ||
            block.world.name != frontier.worldName || !block.world.worldBorder.isInside(block.location))) {
            reject(event, net.lumalyte.lg.interaction.listeners.FrontierTheme.message("War banner", "Place war banners inside the Overworld border."))
            return
        }
        val result = core.place(
            transactionId = UUID.randomUUID(),
            playerId = player.uniqueId,
            guildId = guildId,
            worldId = block.world.uid,
            x = block.x,
            y = block.y,
            z = block.z,
            now = System.currentTimeMillis(),
        ) { state ->
            bukkit.renderPlaced(block, guild, state)
        }

        when (result) {
            is WarBannerPlacementResult.Placed -> {
                if (frontier.enabled) {
                    broadcast(net.lumalyte.lg.interaction.listeners.FrontierTheme.message("War banner",
                        "${guild.name} has placed a war banner! Rally available for up to 15 minutes, until the raid window closes.", true))
                    org.bukkit.Bukkit.getOnlinePlayers().forEach {
                        net.lumalyte.lg.interaction.listeners.FrontierFeedback.show(it, "banner",
                            "WAR BANNER RAISED", guild.name, danger = true, important = true)
                    }
                } else {
                    player.sendMessage(lang.msg("war_banner.feedback.placed", "cost" to result.cost))
                    broadcast(lang.msg("war_banner.broadcast.placed", "guild" to guild.name))
                }
            }
            is WarBannerPlacementResult.PaymentUnknown -> {
                player.sendMessage(lang.msg("war_banner.feedback.payment_unknown"))
                broadcast(lang.msg("war_banner.broadcast.placed", "guild" to guild.name))
            }
            is WarBannerPlacementResult.Cooldown -> reject(
                event,
                lang.msg(
                    "war_banner.feedback.cooldown",
                    "seconds" to ((result.remainingMillis + 999L) / 1000L),
                ),
            )
            WarBannerPlacementResult.ActiveBanner ->
                reject(event, lang.msg("war_banner.feedback.already_active"))
            WarBannerPlacementResult.NotMember ->
                reject(event, lang.msg("war_banner.feedback.not_member"))
            WarBannerPlacementResult.NoPermission ->
                reject(event, lang.msg("war_banner.feedback.no_permission"))
            WarBannerPlacementResult.NoActiveWar ->
                reject(event, if (frontier.enabled) net.lumalyte.lg.interaction.listeners.FrontierTheme.message("War banner", "War banners are only usable during the raid window.") else lang.msg("war_banner.feedback.no_active_war"))
            WarBannerPlacementResult.InsufficientGold ->
                reject(event, lang.msg("war_banner.feedback.insufficient_gold"))
            WarBannerPlacementResult.PaymentUnavailable ->
                reject(event, lang.msg("war_banner.feedback.payment_unavailable"))
            WarBannerPlacementResult.ConfigurationError ->
                reject(event, lang.msg("war_banner.feedback.configuration_error"))
            is WarBannerPlacementResult.Failed -> {
                if (result.compensated) {
                    reject(event, lang.msg("war_banner.feedback.failed_refunded"))
                } else {
                    reject(event, lang.msg("war_banner.feedback.failed_uncertain"))
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onBreak(event: BlockBreakEvent) {
        val banner = event.block.state as? Banner ?: return
        if (!banner.persistentDataContainer.has(
                PluginKeys.WAR_BANNER_ID,
                PersistentDataType.STRING,
            )
        ) return

        if (frontier.enabled && !damageBanner(event.block, 1)) {
            event.isCancelled = true
            net.lumalyte.lg.interaction.listeners.FrontierTheme.cue(event.player, net.lumalyte.lg.interaction.listeners.FrontierTheme.Cue.HIT)
            event.player.sendMessage(net.lumalyte.lg.interaction.listeners.FrontierTheme.message("War banner", "The banner resists. Keep attacking; TNT deals heavier damage."))
            return
        }
        event.isCancelled = false
        event.isDropItems = false
        val state = core.destroyAt(
            event.block.world.uid,
            event.block.x,
            event.block.y,
            event.block.z,
        )
        state?.let(::broadcastDestroyed)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        if (!frontier.enabled) { event.blockList().forEach(::destroyExploded); return }
        val banners = event.blockList().filter(::marked)
        event.blockList().removeAll(banners.toSet())
        if (event.entity is org.bukkit.entity.TNTPrimed || event.entity is org.bukkit.entity.minecart.ExplosiveMinecart) {
            banners.forEach { block ->
                if (damageBanner(block, frontier.tntVaultDamage)) {
                    core.destroyAt(block.world.uid, block.x, block.y, block.z)?.let { state ->
                        bukkit.removePhysical(state); broadcastDestroyed(state)
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        if (frontier.enabled) event.blockList().removeIf(::marked)
        else event.blockList().forEach(::destroyExploded)
    }

    private fun destroyExploded(block: org.bukkit.block.Block) {
        val banner = block.state as? Banner ?: return
        if (!banner.persistentDataContainer.has(
                PluginKeys.WAR_BANNER_ID,
                PersistentDataType.STRING,
            )
        ) return
        core.destroyAt(block.world.uid, block.x, block.y, block.z)
            ?.let(::broadcastDestroyed)
    }

    private fun reject(event: BlockPlaceEvent, message: Component) {
        event.isCancelled = true
        event.player.sendMessage(message)
    }

    private fun broadcastDestroyed(state: WarBannerState) {
        val guildName = guildService.getGuild(state.guildId)?.name
            ?: state.guildId.toString().take(8)
        broadcast(lang.msg("war_banner.broadcast.destroyed", "guild" to guildName))
    }

    private fun broadcast(message: Component) {
        Bukkit.getOnlinePlayers().forEach { it.sendMessage(message) }
        Bukkit.getConsoleSender().sendMessage(message)
    }
}
