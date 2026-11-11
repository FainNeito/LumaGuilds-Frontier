package net.lumalyte.lg.application.actions.claim

import net.lumalyte.lg.application.errors.DatabaseOperationException
import net.lumalyte.lg.application.persistence.ClaimRepository
import net.lumalyte.lg.application.results.claim.ConvertClaimToGuildResult
import net.lumalyte.lg.application.services.GuildService
import net.lumalyte.lg.application.services.GuildRolePermissionResolver
import java.util.UUID

class ConvertClaimToGuild(
    private val claimRepository: ClaimRepository,
    private val guildService: GuildService,
    private val guildRolePermissionResolver: GuildRolePermissionResolver,
    private val config: net.lumalyte.lg.config.MainConfig = net.lumalyte.lg.config.MainConfig(),
    private val chunkBudget: net.lumalyte.lg.application.services.ChunkClaimBudget? = null,
    private val frontierReplacement: net.lumalyte.lg.application.services.FrontierReplacementPolicy? = null,
    private val getClaimAtPosition: GetClaimAtPosition? = null,
) {

    /**
     * Converts a personal claim to a guild claim, making it accessible to guild members based on their roles.
     *
     * @param claimId The UUID of the claim to convert.
     * @param playerId The UUID of the player performing the action (must be claim owner).
     * @return A ConvertClaimToGuildResult indicating the outcome.
     */
    fun execute(claimId: UUID, playerId: UUID): ConvertClaimToGuildResult {
        if (config.frontier.enabled) return ConvertClaimToGuildResult.RaidLocked
        try {
            // Check if claim exists and get it
            val claim = claimRepository.getById(claimId)
                ?: return ConvertClaimToGuildResult.ClaimNotFound

            // Verify player owns the claim
            if (claim.playerId != playerId) {
                return ConvertClaimToGuildResult.NotClaimOwner
            }

            // Check if claim is already guild-owned
            if (claim.teamId != null) {
                return ConvertClaimToGuildResult.AlreadyGuildOwned
            }

            // Check if player is in a guild
            val guildId = guildService.getPlayerGuilds(playerId).firstOrNull()?.id
                ?: return ConvertClaimToGuildResult.PlayerNotInGuild

            if (frontierReplacement?.allows(claim) == false || frontierReplacement?.allows(
                    net.lumalyte.lg.application.services.FrontierOwner(
                        net.lumalyte.lg.application.services.FrontierOwnerType.GUILD, guildId)) == false)
                return ConvertClaimToGuildResult.RaidLocked

            if (config.frontier.enabled) {
                val guild = guildService.getGuild(guildId) ?: return ConvertClaimToGuildResult.PlayerNotInGuild
                val vault = guild.vaultChestLocation ?: return ConvertClaimToGuildResult.VaultRequired
                if (guild.vaultStatus != net.lumalyte.lg.domain.entities.VaultStatus.AVAILABLE)
                    return ConvertClaimToGuildResult.VaultRequired
                val locationClaim = getClaimAtPosition?.execute(vault.worldId,
                    net.lumalyte.lg.domain.values.Position3D(vault.x, vault.y, vault.z))
                val vaultClaim = (locationClaim as? net.lumalyte.lg.application.results.claim.GetClaimAtPositionResult.Success)?.claim
                if (vaultClaim == null || (vaultClaim.teamId != guildId && vaultClaim.id != claim.id))
                    return ConvertClaimToGuildResult.VaultRequired
            }

            if (config.claimGeometry == net.lumalyte.lg.config.ClaimGeometry.CHUNKS) {
                val budget = requireNotNull(chunkBudget)
                if (!budget.canAdd(playerId, guildId, budget.size(claim), config.guildChunkLimit)) {
                    return ConvertClaimToGuildResult.LimitExceeded
                }
            }

            // Convert claim to guild ownership
            val updatedClaim = claim.copy(teamId = guildId)
            val success = claimRepository.update(updatedClaim)

            if (!success) {
                return ConvertClaimToGuildResult.StorageError
            }

            // Invalidate permission cache for this claim to ensure guild permissions are recalculated
            guildRolePermissionResolver.invalidateGuildCache(guildId)

            return ConvertClaimToGuildResult.Success

        } catch (error: DatabaseOperationException) {
            println("Error converting claim to guild: ${error.message}")
            return ConvertClaimToGuildResult.StorageError
        } catch (error: Exception) {
            println("Unexpected error converting claim to guild: ${error.message}")
            return ConvertClaimToGuildResult.StorageError
        }
    }
}
