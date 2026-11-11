package net.lumalyte.lg.interaction.listeners

import net.lumalyte.lg.application.persistence.*
import net.lumalyte.lg.application.services.MemberService
import net.lumalyte.lg.application.services.FrontierRaidClaims
import net.lumalyte.lg.config.FrontierConfig
import net.lumalyte.lg.domain.values.FrontierChunk
import net.lumalyte.lg.domain.values.Position3D
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import java.time.Instant

/** Transient attribute: does not overwrite potions or persist mining penalties on logout/restart. */
class FrontierRaidMining(private val plugin: JavaPlugin, private val config: FrontierConfig,
    private val claims: ClaimRepository, private val partitions: PartitionRepository, private val members: MemberService,
    private val raidClaims: FrontierRaidClaims? = null) : Listener {
    private val key = NamespacedKey(plugin,"frontier_raid_mining")
    private fun clear(player: Player) {
        val attribute = player.getAttribute(Attribute.BLOCK_BREAK_SPEED) ?: return
        attribute.modifiers.filter { it.key == key }.forEach(attribute::removeModifier)
    }
    fun start() {
        plugin.server.scheduler.runTaskTimer(plugin,Runnable {
            val active = config.schedule.stateAt(Instant.now()).active
            for (player in plugin.server.onlinePlayers) {
                val block = player.getTargetBlockExact(6)
                val claim = block?.let { b -> partitions.getByPosition(Position3D(b.x,b.y,b.z)).mapNotNull { claims.getById(it.claimId) }.firstOrNull { it.worldId == b.world.uid } }
                val forward = block?.let { raidClaims?.at(FrontierChunk.atBlock(it.world.uid, it.x, it.z), System.currentTimeMillis()) }
                val hostile = (claim != null && !(claim.teamId?.let { members.getMember(player.uniqueId,it) != null } ?: (claim.playerId == player.uniqueId))) ||
                    (forward != null && members.getMember(player.uniqueId, forward.attacker) == null)
                val attribute = player.getAttribute(Attribute.BLOCK_BREAK_SPEED) ?: continue
                val applied = attribute.modifiers.any { it.key == key }
                if (active && hostile && !applied) attribute.addTransientModifier(AttributeModifier(key,config.raidMiningSpeed-1.0,AttributeModifier.Operation.MULTIPLY_SCALAR_1))
                else if ((!active || !hostile) && applied) clear(player)
            }
        },1L,2L)
    }
    @EventHandler fun onQuit(event: PlayerQuitEvent) = clear(event.player)
    @EventHandler fun onDisable(event: org.bukkit.event.server.PluginDisableEvent) {
        if (event.plugin == plugin) plugin.server.onlinePlayers.forEach(::clear)
    }
}
