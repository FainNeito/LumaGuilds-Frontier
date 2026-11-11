package net.lumalyte.lg.application.services

import net.lumalyte.lg.application.persistence.ClaimRepository
import net.lumalyte.lg.application.persistence.PartitionRepository
import net.lumalyte.lg.domain.entities.Claim
import java.util.UUID

/** Ownership, not the player who originally placed the anchor, determines the pool. */
class ChunkClaimBudget(
    private val claims: ClaimRepository,
    private val partitions: PartitionRepository,
) {
    fun used(playerId: UUID, guildId: UUID? = null): Long {
        val owned = if (guildId == null) claims.getByPlayer(playerId).filter { it.teamId == null }
            else claims.getByTeam(guildId)
        return owned.sumOf { size(it) }
    }

    fun size(claim: Claim): Long = partitions.getByClaim(claim.id).sumOf {
        val lower = it.area.lowerPosition2D
        val upper = it.area.upperPosition2D
        val width = Math.floorDiv(upper.x, 16).toLong() - Math.floorDiv(lower.x, 16) + 1
        val depth = Math.floorDiv(upper.z, 16).toLong() - Math.floorDiv(lower.z, 16) + 1
        width * depth
    }

    fun canAdd(playerId: UUID, guildId: UUID?, additionalChunks: Long, limit: Int): Boolean {
        require(additionalChunks >= 0) { "Additional chunk count cannot be negative" }
        require(limit >= 0) { "Chunk allowance cannot be negative" }
        val remaining = limit.toLong() - used(playerId, guildId)
        return additionalChunks <= remaining
    }
}
