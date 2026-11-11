package net.lumalyte.lg.application.actions.claim

import net.lumalyte.lg.application.persistence.ClaimRepository
import net.lumalyte.lg.application.persistence.PartitionRepository
import net.lumalyte.lg.application.services.GuildService
import net.lumalyte.lg.application.services.PlayerMetadataService
import net.lumalyte.lg.application.services.WorldManipulationService
import net.lumalyte.lg.config.MainConfig
import net.lumalyte.lg.domain.entities.Claim
import net.lumalyte.lg.domain.entities.Partition
import net.lumalyte.lg.domain.values.Position3D
import java.util.UUID

class CreateClaim(private val claimRepository: ClaimRepository, private val partitionRepository: PartitionRepository,
                  private val playerMetadataService: PlayerMetadataService,
                  private val worldManipulationService: WorldManipulationService,
                  private val guildService: GuildService,
                  private val config: MainConfig,
                  private val frontierReplacement: net.lumalyte.lg.application.services.FrontierReplacementPolicy? = null,
                  private val ranks: net.lumalyte.lg.application.services.RankService? = null,
) {

    fun execute(playerId: UUID, name: String, position3D: Position3D, worldId: UUID): net.lumalyte.lg.application.results.claim.CreateClaimResult {
        if (frontierReplacement?.allows(net.lumalyte.lg.application.services.FrontierOwner(
                net.lumalyte.lg.application.services.FrontierOwnerType.PERSONAL, playerId)) == false)
            return net.lumalyte.lg.application.results.claim.CreateClaimResult.RaidLocked
        if (config.frontier.enabled) return executeFrontier(playerId, name, position3D, worldId)
        // All claims start as personal claims - no automatic guild ownership
        val guildId: UUID? = null

        // Get claims for individual player (all claims are personal by default)
        val existingClaims = claimRepository.getByPlayer(playerId)

        // Check individual claim limits
        val claimLimit = playerMetadataService.getPlayerClaimLimit(playerId)

        val chunkMode = config.claimGeometry == net.lumalyte.lg.config.ClaimGeometry.CHUNKS
        val chunkBudget = net.lumalyte.lg.application.services.ChunkClaimBudget(claimRepository, partitionRepository)
        if (if (chunkMode) !chunkBudget.canAdd(playerId, null, 1, config.personalChunkLimit)
            else existingClaims.count() >= claimLimit) {
            return net.lumalyte.lg.application.results.claim.CreateClaimResult.LimitExceeded
        }

        // Check if input name is blank
        if (name.isBlank()) {
            return net.lumalyte.lg.application.results.claim.CreateClaimResult.NameCannotBeBlank
        }

        // Check if name already exists for this player
        val existingClaim = claimRepository.getByName(playerId, name)

        if (existingClaim != null) {
            return net.lumalyte.lg.application.results.claim.CreateClaimResult.NameAlreadyExists
        }

        // Generates the partition area based on config
        val area = config.claimGeometry.initialArea(position3D, config.initialClaimSize)
        if (!config.frontier.permitsLand(area))
            return net.lumalyte.lg.application.results.claim.CreateClaimResult.TooCloseToWorldBorder
        if (config.frontier.enabled && IsNewClaimLocationValid(claimRepository, partitionRepository,
                worldManipulationService, config).execute(position3D, worldId, playerId, guildService.getPlayerGuilds(playerId).map { it.id }.toSet()) !=
                net.lumalyte.lg.application.results.claim.IsNewClaimLocationValidResult.Valid)
            return net.lumalyte.lg.application.results.claim.CreateClaimResult.InvalidLocation

        // Validate area is within world border
        if (!worldManipulationService.isInsideWorldBorder(worldId, area)) {
            return net.lumalyte.lg.application.results.claim.CreateClaimResult.TooCloseToWorldBorder
        }

        // Creates new claim and partition with guild ownership if applicable
        val newClaim = Claim(worldId, playerId, guildId, position3D, name)
        val partition = Partition(newClaim.id, area)
        check(claimRepository.add(newClaim)) { "Failed to save claim" }
        check(partitionRepository.add(partition)) { "Failed to save claim partition" }
        return net.lumalyte.lg.application.results.claim.CreateClaimResult.Success(newClaim)
    }
    fun executeFrontier(playerId: UUID, name: String, position: Position3D, worldId: UUID, guildId: UUID? = null): net.lumalyte.lg.application.results.claim.CreateClaimResult {
        check(config.frontier.enabled)
        if (guildId == null && guildService.getPlayerGuilds(playerId).any { ranks?.getPlayerRank(playerId,it.id)?.priority == 0 })
            return net.lumalyte.lg.application.results.claim.CreateClaimResult.InvalidLocation
        val owner = if (guildId == null) net.lumalyte.lg.application.services.FrontierOwner(net.lumalyte.lg.application.services.FrontierOwnerType.PERSONAL, playerId)
            else net.lumalyte.lg.application.services.FrontierOwner(net.lumalyte.lg.application.services.FrontierOwnerType.GUILD, guildId)
        if (config.frontier.schedule.stateAt(java.time.Instant.now()).active || frontierReplacement?.allows(owner) == false)
            return net.lumalyte.lg.application.results.claim.CreateClaimResult.RaidLocked
        if (guildId != null && guildService.getPlayerGuilds(playerId).none { it.id == guildId })
            return net.lumalyte.lg.application.results.claim.CreateClaimResult.InvalidLocation
        val territory = net.lumalyte.lg.application.services.FrontierTerritory(claimRepository, partitionRepository)
        val foundation = territory.foundation(owner)
        val limit = if (guildId == null) config.personalChunkLimit else config.guildChunkLimit
        if (territory.chunks(owner).size >= limit) return net.lumalyte.lg.application.results.claim.CreateClaimResult.LimitExceeded
        if (name.isBlank() || name.length > 50) return net.lumalyte.lg.application.results.claim.CreateClaimResult.NameCannotBeBlank
        if (!territory.canExtend(owner, worldId, position.getChunk())) return net.lumalyte.lg.application.results.claim.CreateClaimResult.InvalidLocation
        if (IsNewClaimLocationValid(claimRepository, partitionRepository, worldManipulationService, config)
                .execute(position, worldId, playerId, guildId?.let { setOf(it) } ?: emptySet()) != net.lumalyte.lg.application.results.claim.IsNewClaimLocationValidResult.Valid)
            return net.lumalyte.lg.application.results.claim.CreateClaimResult.InvalidLocation
        val claim = foundation ?: Claim(worldId, playerId, guildId, position, name)
        val partition = Partition(claim.id, config.claimGeometry.initialArea(position, config.initialClaimSize))
        if (foundation == null) check(claimRepository.add(claim)) { "Failed to save founding claim" }
        try { check(partitionRepository.add(partition)) { "Failed to save territory chunk" } }
        catch (failure: Exception) { if (foundation == null) claimRepository.remove(claim.id); throw failure }
        return net.lumalyte.lg.application.results.claim.CreateClaimResult.Success(claim)
    }}
