package net.lumalyte.lg.application.actions.claim

import net.lumalyte.lg.application.persistence.ClaimRepository
import net.lumalyte.lg.application.persistence.PartitionRepository
import net.lumalyte.lg.application.results.claim.IsNewClaimLocationValidResult
import net.lumalyte.lg.application.services.WorldManipulationService
import net.lumalyte.lg.config.MainConfig
import net.lumalyte.lg.domain.entities.Partition
import net.lumalyte.lg.domain.values.Area
import net.lumalyte.lg.domain.values.Position
import net.lumalyte.lg.domain.values.Position2D
import java.util.UUID

class IsNewClaimLocationValid(private val claimRepository: ClaimRepository,
                              private val partitionRepository: PartitionRepository,
                              private val worldManipulationService: WorldManipulationService,
                              private val config: MainConfig) {
    fun execute(position: Position, worldId: UUID, playerId: UUID? = null, guildIds: Set<UUID> = emptySet()): IsNewClaimLocationValidResult {
        if (config.frontier.enabled && worldManipulationService.isClaimWorld(worldId, config.frontier.worldName) == false)
            return IsNewClaimLocationValidResult.TooCloseToWorldBorder
        val padding = if (config.frontier.enabled) config.frontier.foreignClaimBufferChunks * 16 else config.distanceBetweenClaims
        // Create the area that is required for the partition
        val area = config.claimGeometry.initialArea(position, config.initialClaimSize)
        if (!config.frontier.permitsLand(area)) return IsNewClaimLocationValidResult.TooCloseToWorldBorder

        // Get the partitions that exist in the occupied chunk space
        val chunks = area.getChunks().flatMap { getSurroundingPositions(it, maxOf(1, (padding + 15) / 16)) }
        val partitions = chunks.flatMap { getByChunk(worldId, it) }.toSet()


        // Check if the area with the padded boundary overlaps
        val areaWithBoundary = Area(
            Position2D( area.lowerPosition2D.x - padding,
                area.lowerPosition2D.z - padding),
            Position2D( area.upperPosition2D.x + padding,
                area.upperPosition2D.z + padding))
        for (partition in partitions) {
            val claim = claimRepository.getById(partition.claimId)
            val related = config.frontier.enabled && claim != null &&
                (if (claim.teamId != null) claim.teamId in guildIds else claim.playerId == playerId)
            if (partition.isAreaOverlap(if (related) area else areaWithBoundary)) {
                return IsNewClaimLocationValidResult.Overlap
            }
        }

        // Validate area is within world border
        if (!worldManipulationService.isInsideWorldBorder(worldId, if (config.frontier.enabled) area else areaWithBoundary)) {
            return IsNewClaimLocationValidResult.TooCloseToWorldBorder
        }

        return IsNewClaimLocationValidResult.Valid
    }

    private fun getSurroundingPositions(position: Position2D, radius: Int): List<Position2D> {
        val positions = mutableListOf<Position2D>()

        for (i in -1 * radius..1 * radius) {
            for (j in -1 * radius..1 * radius) {
                positions.add(Position2D(position.x + i, position.z + j))
            }
        }
        return positions
    }

    private fun getByChunk(worldId: UUID, position2D: Position2D): Set<Partition> {
        return filterByWorld(worldId, partitionRepository.getByChunk(Position2D(position2D.x, position2D.z)))
    }

    private fun filterByWorld(worldId: UUID, inputPartitions: Set<Partition>): Set<Partition> {
        val outputPartitions = mutableSetOf<Partition>()
        for (partition in inputPartitions) {
            val claimPartition = claimRepository.getById(partition.claimId) ?: continue
            if (claimPartition.worldId == worldId) {
                outputPartitions.add(partition)
            }
        }
        return outputPartitions
    }
}
