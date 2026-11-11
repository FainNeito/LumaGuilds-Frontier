package net.lumalyte.lg.application.services

import net.lumalyte.lg.application.persistence.ClaimRepository
import net.lumalyte.lg.application.persistence.PartitionRepository
import net.lumalyte.lg.domain.values.FrontierChunk
import net.lumalyte.lg.domain.values.Position2D
import java.util.UUID

/** Permanent guild territory is the only valid location for a Frontier spawner. */
class FrontierSpawnerPlacement(
    private val claims: ClaimRepository,
    private val partitions: PartitionRepository,
    private val isRaidClaim: (FrontierChunk, Long) -> Boolean,
) {
    fun allowed(chunk: FrontierChunk, playerGuilds: Set<UUID>, now: Long): Boolean {
        if (playerGuilds.isEmpty() || isRaidClaim(chunk, now)) return false
        val records = partitions.getByChunk(Position2D(chunk.x, chunk.z)).map { claims.getById(it.claimId) }
        if (records.any { it == null }) return false
        val owners = records.filterNotNull().filter { it.worldId == chunk.worldId }
        // Fail closed on wilderness, personal land, and corrupt overlapping ownership.
        return owners.isNotEmpty() && owners.all { it.teamId?.let(playerGuilds::contains) == true }
    }
}
