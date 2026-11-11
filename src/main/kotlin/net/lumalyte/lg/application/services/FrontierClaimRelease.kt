package net.lumalyte.lg.application.services

import net.lumalyte.lg.application.persistence.ClaimRepository
import net.lumalyte.lg.application.persistence.PartitionRepository
import net.lumalyte.lg.application.persistence.ClaimPermissionRepository
import net.lumalyte.lg.application.persistence.ClaimFlagRepository
import net.lumalyte.lg.application.persistence.PlayerAccessRepository
import net.lumalyte.lg.domain.entities.Claim
import java.util.UUID

enum class FrontierOwnerType { PERSONAL, GUILD }

data class FrontierOwner(val type: FrontierOwnerType, val id: UUID) {
    fun owns(claim: Claim): Boolean = when (type) {
        FrontierOwnerType.PERSONAL -> claim.teamId == null && claim.playerId == id
        FrontierOwnerType.GUILD -> claim.teamId == id
    }
}

data class FrontierReleasePlan(val id: UUID, val owner: FrontierOwner, val claimIds: Set<UUID>)

/** Implementations must durably save before returning, or throw. */
interface FrontierReleaseJournal {
    fun save(plan: FrontierReleasePlan)
    fun pending(): List<FrontierReleasePlan>
    fun complete(id: UUID)
}

/** Run on the server thread. Failures retain the exact snapshot for recovery. */
class FrontierClaimRelease(
    private val claims: ClaimRepository,
    private val partitions: PartitionRepository,
    private val permissions: ClaimPermissionRepository,
    private val flags: ClaimFlagRepository,
    private val access: PlayerAccessRepository,
    private val journal: FrontierReleaseJournal,
) {
    fun snapshot(owner: FrontierOwner): Set<UUID> {
        val owned = when (owner.type) {
            FrontierOwnerType.PERSONAL -> claims.getByPlayer(owner.id)
            FrontierOwnerType.GUILD -> claims.getByTeam(owner.id)
        }.filter(owner::owns)
        return owned.map { it.id }.toSet()
    }

    fun release(owner: FrontierOwner): FrontierReleasePlan =
        release(FrontierReleasePlan(UUID.randomUUID(), owner, snapshot(owner)))

    /** Use the persisted siege plan on every retry; never resnapshot newly acquired land. */
    fun release(plan: FrontierReleasePlan): FrontierReleasePlan {
        journal.save(plan)
        resume(plan)
        return plan
    }

    fun recover() {
        journal.pending().forEach(::resume)
    }

    private fun resume(plan: FrontierReleasePlan) {
        for (id in plan.claimIds) {
            val claim = claims.getById(id)
            // A new owner must never lose land to an old destruction record.
            if (claim != null && !plan.owner.owns(claim)) continue
            if (claim != null) check(claims.remove(id)) { "Failed to release claim $id" }
            // Missing claim means an earlier attempt may have stopped during cleanup.
            // Repository cleanup returns false for already-absent rows; that is safe.
            access.removeByClaim(id)
            permissions.removeByClaim(id)
            flags.removeByClaim(id)
            partitions.removeByClaim(id)
        }
        journal.complete(plan.id)
    }
}
