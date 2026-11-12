package net.lumalyte.lg.application.services

import net.lumalyte.lg.application.persistence.ClaimRepository
import net.lumalyte.lg.application.persistence.PartitionRepository
import net.lumalyte.lg.domain.values.Position3D
import java.util.UUID

/** Remote storage uses real ownership, never ally access or staff build bypasses. */
class FrontierVaultAccess(private val claims: ClaimRepository, private val partitions: PartitionRepository) {
    fun allowed(guildId: UUID, worldId: UUID, position: Position3D, inCombat: Boolean): Boolean =
        !inCombat && partitions.getByPosition(position).mapNotNull { claims.getById(it.claimId) }
            .any { it.worldId == worldId && it.teamId == guildId }
}
