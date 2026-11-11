package net.lumalyte.lg.application.actions.claim.transfer

import net.lumalyte.lg.application.errors.DatabaseOperationException
import net.lumalyte.lg.application.persistence.ClaimRepository
import net.lumalyte.lg.application.persistence.ClaimTransferRequestRepository
import net.lumalyte.lg.application.persistence.PartitionRepository
import net.lumalyte.lg.application.results.claim.transfer.AcceptTransferRequestResult
import net.lumalyte.lg.application.services.PlayerMetadataService
import java.time.Instant
import java.util.UUID

class AcceptTransferRequest(
    private val claimRepository: ClaimRepository,
    private val playerMetadataService: PlayerMetadataService,
    private val partitionRepository: PartitionRepository,
    private val transferRequests: ClaimTransferRequestRepository,
    private val config: net.lumalyte.lg.config.MainConfig = net.lumalyte.lg.config.MainConfig(),
    private val frontierReplacement: net.lumalyte.lg.application.services.FrontierReplacementPolicy? = null,
) {
    fun execute(claimId: UUID, playerId: UUID, newName: String): AcceptTransferRequestResult {
        if (config.frontier.enabled) return AcceptTransferRequestResult.RaidLocked
        val claim = claimRepository.getById(claimId)
            ?: return AcceptTransferRequestResult.ClaimNotFound
        if (claim.playerId == playerId) return AcceptTransferRequestResult.PlayerOwnsClaim
        if (frontierReplacement?.allows(claim) == false || (claim.teamId == null &&
                frontierReplacement?.allows(net.lumalyte.lg.application.services.FrontierOwner(
                    net.lumalyte.lg.application.services.FrontierOwnerType.PERSONAL, playerId)) == false))
            return AcceptTransferRequestResult.RaidLocked

        try {
            if (!transferRequests.hasActive(claimId, playerId, Instant.now().epochSecond)) {
                return AcceptTransferRequestResult.NoActiveTransferRequest
            }
        } catch (_: DatabaseOperationException) {
            return AcceptTransferRequestResult.StorageError
        }

        if (config.claimGeometry == net.lumalyte.lg.config.ClaimGeometry.CHUNKS) {
            val budget = net.lumalyte.lg.application.services.ChunkClaimBudget(claimRepository, partitionRepository)
            if (claim.teamId == null && !budget.canAdd(playerId, null, budget.size(claim), config.personalChunkLimit)) {
                return AcceptTransferRequestResult.BlockLimitExceeded
            }
        } else {
        val playerClaimLimit = playerMetadataService.getPlayerClaimLimit(playerId)
        val playerClaims = claimRepository.getByPlayer(playerId)
        if (playerClaims.size >= playerClaimLimit) {
            return AcceptTransferRequestResult.ClaimLimitExceeded
        }

        val playerBlockLimit = playerMetadataService.getPlayerClaimBlockLimit(playerId)
        val playerBlockCount = playerClaims.flatMap { playerClaim ->
            partitionRepository.getByClaim(playerClaim.id)
        }.sumOf { it.getBlockCount() }
        val claimBlockCount = partitionRepository.getByClaim(claim.id).sumOf { it.getBlockCount() }
        if (playerBlockCount + claimBlockCount > playerBlockLimit) {
            return AcceptTransferRequestResult.BlockLimitExceeded
        }

        }

        if (claimRepository.getByName(playerId, newName) != null) {
            return AcceptTransferRequestResult.NameAlreadyExists
        }

        // A transfer must not turn adjacent same-owner land into adjacent foreign land.
        if (config.frontier.enabled && claim.teamId == null) {
            val padding = config.frontier.foreignClaimBufferChunks * 16
            for (partition in partitionRepository.getByClaim(claim.id)) {
                val area = partition.area
                val boundary = net.lumalyte.lg.domain.values.Area(
                    net.lumalyte.lg.domain.values.Position2D(area.lowerPosition2D.x - padding, area.lowerPosition2D.z - padding),
                    net.lumalyte.lg.domain.values.Position2D(area.upperPosition2D.x + padding, area.upperPosition2D.z + padding))
                val conflict = boundary.getChunks().flatMap { partitionRepository.getByChunk(it) }.any {
                    val other = claimRepository.getById(it.claimId)
                    other != null && other.id != claim.id && other.worldId == claim.worldId &&
                        (other.teamId != null || other.playerId != playerId) && it.isAreaOverlap(boundary)
                }
                if (conflict) return AcceptTransferRequestResult.BufferConflict
            }
        }

        return try {
            // Atomically consume this receiver's offer and every competing offer. This is
            // the acceptance token: once one receiver consumes the rows, concurrent
            // acceptors cannot proceed using a stale pre-check.
            if (!transferRequests.consumeClaim(claimId, playerId, Instant.now().epochSecond)) {
                return AcceptTransferRequestResult.NoActiveTransferRequest
            }
            val updatedClaim = claim.copy(
                playerId = playerId,
                name = newName,
            )
            // The durable owner may have changed through another path after our initial
            // read. Never overwrite a newer owner from a stale snapshot.
            if (!claimRepository.updateIfOwnedBy(updatedClaim, claim.playerId)) {
                return AcceptTransferRequestResult.StorageError
            }
            AcceptTransferRequestResult.Success
        } catch (_: DatabaseOperationException) {
            AcceptTransferRequestResult.StorageError
        }
    }
}
