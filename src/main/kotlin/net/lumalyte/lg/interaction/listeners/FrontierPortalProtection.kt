package net.lumalyte.lg.interaction.listeners

import net.lumalyte.lg.application.persistence.ClaimRepository
import net.lumalyte.lg.application.persistence.PartitionRepository
import net.lumalyte.lg.application.services.MemberService
import net.lumalyte.lg.config.FrontierConfig
import net.lumalyte.lg.domain.values.Position3D
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.world.PortalCreateEvent
import java.time.Instant

internal object FrontierPortalPolicy {
    fun block(raiding: Boolean,claimed: Boolean,member: Boolean) = !raiding && claimed && !member
}

/** Check the actual teleport destination and every block of an automatically created portal. */
internal class FrontierPortalProtection(private val config: FrontierConfig,private val claims: ClaimRepository,
    private val partitions: PartitionRepository,private val members: MemberService) : Listener {
    private fun blocked(location: Location,player: Player?): Boolean {
        val world=location.world
        if(world.environment!=World.Environment.NORMAL || world.name!=config.worldName) return false
        val pos=Position3D(location.blockX,location.blockY,location.blockZ)
        val claim=partitions.getByPosition(pos).asSequence().mapNotNull { claims.getById(it.claimId) }
            .firstOrNull { it.worldId==world.uid } ?: return false
        val member=player!=null && (claim.teamId?.let { members.getMember(player.uniqueId,it)!=null } ?: (claim.playerId==player.uniqueId))
        return FrontierPortalPolicy.block(config.schedule.stateAt(Instant.now()).active,true,member)
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun teleport(event: PlayerTeleportEvent) {
        if(event is PlayerPortalEvent || event.cause!=PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) return
        if(event.to?.let { blocked(it,event.player) }==true) {
            event.isCancelled=true
            event.player.sendMessage(FrontierTheme.message("Claim","You cannot enter another guild's protected territory through a Nether portal outside raids.",true))
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun create(event: PortalCreateEvent) {
        if(event.reason!=PortalCreateEvent.CreateReason.NETHER_PAIR) return
        val player=event.entity as? Player
        if(event.blocks.any { blocked(it.location,player) }) {
            event.isCancelled=true
            player?.sendMessage(FrontierTheme.message("Claim","A Nether portal cannot generate inside another guild's protected territory outside raids.",true))
        }
    }
}
