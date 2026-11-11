package net.lumalyte.lg.application.actions.claim.metadata

import net.lumalyte.lg.application.errors.DatabaseOperationException
import net.lumalyte.lg.application.persistence.ClaimRepository
import net.lumalyte.lg.application.results.claim.metadata.UpdateClaimNameResult
import java.util.UUID

class UpdateClaimName(private val claimRepository: ClaimRepository, private val config: net.lumalyte.lg.config.MainConfig = net.lumalyte.lg.config.MainConfig()) {
    fun execute(claimId: UUID, name: String): UpdateClaimNameResult {
        // Check if claim exists
        val existingClaim = claimRepository.getById(claimId) ?: return UpdateClaimNameResult.ClaimNotFound

        if (config.frontier.enabled) {
            if (name.isBlank() || name.length > 50) return UpdateClaimNameResult.StorageError
            val owner = net.lumalyte.lg.application.services.FrontierReplacementPolicy.ownerOf(existingClaim)
            val owned = (existingClaim.teamId?.let(claimRepository::getByTeam) ?: claimRepository.getByPlayer(existingClaim.playerId)).filter(owner::owns)
            for (claim in owned) if (!claimRepository.update(claim.copy(name = name))) return UpdateClaimNameResult.StorageError
            return UpdateClaimNameResult.Success(existingClaim.copy(name = name))
        }
        // Check if name already exists in player's list of claims
        val duplicate = existingClaim.teamId?.let { claimRepository.getByNameForTeam(it, name) }
            ?: claimRepository.getByName(existingClaim.playerId, name)
        if (duplicate != null && duplicate.id != claimId)
            return UpdateClaimNameResult.NameAlreadyExists

        // Change Name and persist to storage
        val newClaim = existingClaim.copy(name = name)
        try {
            if (!claimRepository.update(newClaim)) return UpdateClaimNameResult.StorageError
            return UpdateClaimNameResult.Success(newClaim)
        } catch (error: DatabaseOperationException) {
            println("Error has occurred trying to save to the database")
            return UpdateClaimNameResult.StorageError
        }
    }
}
