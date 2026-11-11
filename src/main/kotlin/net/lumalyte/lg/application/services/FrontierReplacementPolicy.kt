package net.lumalyte.lg.application.services

import net.lumalyte.lg.config.MainConfig
import net.lumalyte.lg.domain.entities.Claim
import java.time.Duration
import java.time.Instant

/** Shared by placement and ownership changes; does not resolve Bukkit services. */
class FrontierReplacementPolicy(private val config: MainConfig, private val store: FrontierSiegeStore) {
    fun allows(owner: FrontierOwner, now: Instant = Instant.now()): Boolean {
        if (!config.frontier.enabled) return true
        val record = store.get(owner) ?: return true
        if (record.release != null && record.completedAt == null) return false
        if (record.completedAt == null) return !now.isBefore(record.windowEnd)
        return !now.isBefore(record.completedAt.plus(config.frontier.replacementCooldown))
    }

    fun allows(claim: Claim): Boolean = allows(ownerOf(claim))

    companion object {
        fun ownerOf(claim: Claim) = claim.teamId?.let { FrontierOwner(FrontierOwnerType.GUILD, it) }
            ?: FrontierOwner(FrontierOwnerType.PERSONAL, claim.playerId)
    }
}
