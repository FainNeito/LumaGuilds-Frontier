package net.lumalyte.lg.application.actions.claim.anchor

import net.lumalyte.lg.application.persistence.ClaimFlagRepository
import net.lumalyte.lg.application.persistence.ClaimPermissionRepository
import net.lumalyte.lg.application.persistence.ClaimRepository
import net.lumalyte.lg.application.persistence.PartitionRepository
import net.lumalyte.lg.application.persistence.PlayerAccessRepository
import net.lumalyte.lg.application.results.claim.anchor.BreakClaimAnchorResult
import net.lumalyte.lg.domain.values.Position3D
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

class BreakClaimAnchor(private val claimRepository: ClaimRepository,
                       private val partitionRepository: PartitionRepository,
                       private val flagRepository: ClaimFlagRepository,
                       private val claimPermissionRepository: ClaimPermissionRepository,
                       private val playerAccessRepository: PlayerAccessRepository,
                       private val plugin: JavaPlugin,
                       private val config: net.lumalyte.lg.config.MainConfig = net.lumalyte.lg.config.MainConfig()) {

    fun execute(worldId: UUID, position: Position3D): BreakClaimAnchorResult {
        // Frontier destruction must use its durable siege coordinator, including API callers.
        if (config.frontier.enabled) return BreakClaimAnchorResult.StorageError
        val claim = claimRepository.getByPosition(position, worldId) ?: return BreakClaimAnchorResult.ClaimNotFound

        // Trigger the break reset countdown and decrement break count by 1
        claim.resetBreakCount(plugin)
        if (claim.breakCount > 1) {
            claim.breakCount -= 1
            return BreakClaimAnchorResult.ClaimBreaking(claim.breakCount)
        }

        // If break counter met, destroy claim and all associated
        playerAccessRepository.removeByClaim(claim.id)
        claimPermissionRepository.removeByClaim(claim.id)
        flagRepository.removeByClaim(claim.id)
        partitionRepository.removeByClaim(claim.id)
        claimRepository.remove(claim.id)
        return BreakClaimAnchorResult.Success
    }
}
