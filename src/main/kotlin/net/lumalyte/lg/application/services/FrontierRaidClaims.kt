package net.lumalyte.lg.application.services

import net.lumalyte.lg.application.persistence.ClaimRepository
import net.lumalyte.lg.application.persistence.PartitionRepository
import net.lumalyte.lg.config.FrontierConfig
import net.lumalyte.lg.domain.entities.WarBannerState
import net.lumalyte.lg.domain.values.Area
import net.lumalyte.lg.domain.values.FrontierChunk
import net.lumalyte.lg.domain.values.Position2D
import java.time.Instant
import java.util.UUID

/** A forward position is temporary; it is deliberately not a permanent Claim or Partition. */
data class FrontierRaidClaim(
    val attacker: UUID,
    val defender: UUID,
    val chunk: FrontierChunk,
    val createdAt: Long,
    val windowEnd: Long,
    val graceEnd: Long,
    val bannerId: UUID? = null,
    val bannerEnd: Long? = null,
)

interface FrontierRaidClaimStore {
    fun all(): List<FrontierRaidClaim>
    /** Must be durable before returning; failure must leave the previous record intact. */
    fun save(claim: FrontierRaidClaim)
    fun delete(attacker: UUID)
}

sealed interface FrontierRaidClaimCheck {
    data class Allowed(val defender: UUID) : FrontierRaidClaimCheck
    data class Rejected(val reason: String) : FrontierRaidClaimCheck
}

/** Main-thread admission and lifecycle policy. Banner lookup is the source of truth after binding. */
class FrontierRaidClaims(
    private val config: FrontierConfig,
    private val claims: ClaimRepository,
    private val partitions: PartitionRepository,
    private val store: FrontierRaidClaimStore,
    private val banner: (UUID) -> WarBannerState?,
) {
    companion object { const val BANNER_GRACE_MILLIS = 120_000L }

    private val loaded = store.all()
    private val active = loaded.associateBy { it.attacker }.toMutableMap()

    init { require(active.size == loaded.size) { "Duplicate Frontier raid claims" } }

    private fun permanentOwners(chunk: FrontierChunk): Set<UUID?> =
        partitions.getByChunk(Position2D(chunk.x, chunk.z)).mapNotNull { partition ->
            claims.getById(partition.claimId)?.takeIf { it.worldId == chunk.worldId }
        }.map { it.teamId }.toSet()

    private fun live(record: FrontierRaidClaim, now: Long): Boolean {
        if (!config.schedule.stateAt(Instant.ofEpochMilli(now)).active || now >= record.windowEnd) return false
        if (record.bannerId == null) return now < record.graceEnd
        val state = banner(record.attacker) ?: return false
        return state.active && state.bannerId == record.bannerId && state.isActiveAt(now) &&
            state.expiresAt == record.bannerEnd && state.worldId == record.chunk.worldId &&
            Math.floorDiv(state.x, 16) == record.chunk.x && Math.floorDiv(state.z, 16) == record.chunk.z
    }

    @Synchronized fun at(chunk: FrontierChunk, now: Long): FrontierRaidClaim? =
        active.values.firstOrNull { it.chunk == chunk && live(it, now) }

    @Synchronized fun forGuild(attacker: UUID, now: Long): FrontierRaidClaim? =
        active[attacker]?.takeIf { live(it, now) }

    @Synchronized fun reconcile(now: Long) {
        active.values.filterNot { live(it, now) }.forEach { record ->
            store.delete(record.attacker)
            active.remove(record.attacker)
        }
    }

    /** A target must be an edge-adjacent foreign GUILD claim; diagonal/solo land does not qualify. */
    @Synchronized fun check(attacker: UUID, chunk: FrontierChunk, target: UUID?, now: Long): FrontierRaidClaimCheck {
        if (!config.schedule.stateAt(Instant.ofEpochMilli(now)).active)
            return FrontierRaidClaimCheck.Rejected("Raid claims are available only during the raid hour.")
        if (active[attacker]?.let { live(it, now) } == true)
            return FrontierRaidClaimCheck.Rejected("Your guild already has an active raid claim.")
        if (active.values.any { it.chunk == chunk && live(it, now) })
            return FrontierRaidClaimCheck.Rejected("Another guild is already staging in this chunk.")
        val lastBanner = banner(attacker)
        if (lastBanner?.isActiveAt(now) == true || (lastBanner != null && lastBanner.cooldownUntil > now))
            return FrontierRaidClaimCheck.Rejected("Your guild's war banner is active or on cooldown. Wait before staging another raid.")
        val area = Area(Position2D(chunk.x * 16, chunk.z * 16), Position2D(chunk.x * 16 + 15, chunk.z * 16 + 15))
        if (!config.permitsLand(area))
            return FrontierRaidClaimCheck.Rejected("This chunk is outside the territory border or is reserved spawn.")
        if (permanentOwners(chunk).isNotEmpty())
            return FrontierRaidClaimCheck.Rejected("Stand in wilderness, not inside an existing territory.")
        if (claims.getByTeam(attacker).none { it.worldId == chunk.worldId })
            return FrontierRaidClaimCheck.Rejected("Your guild needs a Founding Vault and territory in this world.")
        val neighbors = listOf(
            FrontierChunk(chunk.worldId, chunk.x - 1, chunk.z),
            FrontierChunk(chunk.worldId, chunk.x + 1, chunk.z),
            FrontierChunk(chunk.worldId, chunk.x, chunk.z - 1),
            FrontierChunk(chunk.worldId, chunk.x, chunk.z + 1),
        )
        val defenders = neighbors.flatMap(::permanentOwners).filterNotNull().filter { it != attacker }.toSet()
        val selected = when {
            target != null && target == attacker -> null
            target != null && target in defenders -> target
            target == null && defenders.size == 1 -> defenders.single()
            else -> null
        }
        if (selected == null) return FrontierRaidClaimCheck.Rejected(
            if (defenders.size > 1 && target == null) "More than one guild borders this chunk. Specify /raidclaim <target guild>."
            else "Stand in wilderness directly beside the target guild's territory (not diagonally).")
        return FrontierRaidClaimCheck.Allowed(selected)
    }

    @Synchronized fun create(attacker: UUID, chunk: FrontierChunk, target: UUID?, now: Long): FrontierRaidClaimCheck {
        reconcile(now)
        val result = check(attacker, chunk, target, now)
        if (result !is FrontierRaidClaimCheck.Allowed) return result
        val end = config.schedule.stateAt(Instant.ofEpochMilli(now)).nextTransition.toEpochMilli()
        val record = FrontierRaidClaim(attacker, result.defender, chunk, now, end,
            minOf(end, now + BANNER_GRACE_MILLIS))
        store.save(record)
        active[attacker] = record
        return result
    }

    @Synchronized fun bind(state: WarBannerState, now: Long): Boolean {
        val record = active[state.guildId] ?: return false
        if (record.bannerId != null || !live(record, now) || !state.isActiveAt(now) ||
            state.worldId != record.chunk.worldId || Math.floorDiv(state.x, 16) != record.chunk.x ||
            Math.floorDiv(state.z, 16) != record.chunk.z || state.expiresAt > record.windowEnd) return false
        val bound = record.copy(bannerId = state.bannerId, bannerEnd = state.expiresAt)
        store.save(bound)
        active[state.guildId] = bound
        return true
    }

    @Synchronized fun removeBanner(state: WarBannerState) {
        val record = active[state.guildId] ?: return
        if (record.bannerId != state.bannerId) return
        store.delete(state.guildId)
        active.remove(state.guildId)
    }

    @Synchronized fun abandon(attacker: UUID, now: Long): Boolean {
        val record = active[attacker] ?: return false
        if (record.bannerId != null && live(record, now)) return false
        store.delete(attacker)
        active.remove(attacker)
        return true
    }
}
