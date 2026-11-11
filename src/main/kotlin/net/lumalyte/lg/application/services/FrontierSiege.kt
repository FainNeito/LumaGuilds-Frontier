package net.lumalyte.lg.application.services

import net.lumalyte.lg.domain.values.FrontierRaidSchedule
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** A pending defeat retains the exact land snapshot, even if cleanup must be retried. */
data class FrontierSiegeRecord(
    val owner: FrontierOwner,
    val objective: String,
    val windowEnd: Instant,
    val hits: Int,
    val lastHit: Instant,
    val release: FrontierReleasePlan? = null,
    val completedAt: Instant? = null,
)

interface FrontierSiegeStore {
    fun get(owner: FrontierOwner): FrontierSiegeRecord?
    fun save(record: FrontierSiegeRecord)
    fun all(): List<FrontierSiegeRecord>
}

sealed interface FrontierSiegeResult {
    data object Protected : FrontierSiegeResult
    data object Defender : FrontierSiegeResult
    data object TooSoon : FrontierSiegeResult
    data object DifferentObjective : FrontierSiegeResult
    data class Progress(val hits: Int, val required: Int) : FrontierSiegeResult
    data class Cooldown(val until: Instant) : FrontierSiegeResult
    data object Defeated : FrontierSiegeResult
}

/** Server-thread coordinator. The defeat callback must be idempotent and preserve vault items. */
class FrontierSiege(
    private val schedule: FrontierRaidSchedule,
    private val store: FrontierSiegeStore,
    private val snapshot: (FrontierOwner) -> Set<UUID>,
    private val defeat: (FrontierSiegeRecord) -> Unit,
    private val requiredHits: Int = 20,
    private val hitInterval: Duration = Duration.ofSeconds(2),
    private val replacementCooldown: Duration = Duration.ofMinutes(30),
) {
    init {
        require(requiredHits > 0)
        require(!hitInterval.isNegative && !hitInterval.isZero)
        require(!replacementCooldown.isNegative)
    }

    fun hit(owner: FrontierOwner, objective: String, defender: Boolean, now: Instant, damage: Int = 1): FrontierSiegeResult {
        require(damage in 1..requiredHits)
        if (defender) return FrontierSiegeResult.Defender
        val window = schedule.stateAt(now)
        if (!window.active) return FrontierSiegeResult.Protected
        val old = store.get(owner)
        // Once destruction has committed to the journal, it must finish even after the window closes.
        if (old?.release != null && old.completedAt == null) {
            finish(old, now)
            return FrontierSiegeResult.Defeated
        }
        old?.completedAt?.plus(replacementCooldown)?.let { until ->
            if (now.isBefore(until)) return FrontierSiegeResult.Cooldown(until)
        }
        // Changing objectives must not reset damage or bypass the hit interval.
        if (old != null && old.completedAt == null && old.windowEnd == window.nextTransition &&
            old.objective != objective) return FrontierSiegeResult.DifferentObjective
        val current = old?.takeIf {
            it.completedAt == null && it.objective == objective && it.windowEnd == window.nextTransition
        }
        if (current != null && now.isBefore(current.lastHit.plus(hitInterval))) return FrontierSiegeResult.TooSoon
        val hits = minOf(requiredHits, (current?.hits ?: 0) + damage)
        val plan = if (hits >= requiredHits)
            FrontierReleasePlan(UUID.randomUUID(), owner, snapshot(owner)) else null
        val record = FrontierSiegeRecord(owner, objective, window.nextTransition, hits, now, plan)
        store.save(record)
        if (plan == null) return FrontierSiegeResult.Progress(hits, requiredHits)
        finish(record, now)
        return FrontierSiegeResult.Defeated
    }

    /** Invoke before accepting claim/objective mutations on startup. */
    fun recover(now: Instant) {
        store.all().filter { it.release != null && it.completedAt == null }.forEach { finish(it, now) }
    }

    fun canReplace(owner: FrontierOwner, now: Instant): Boolean {
        val record = store.get(owner) ?: return true
        if (record.release != null && record.completedAt == null) return false
        if (record.completedAt == null && now.isBefore(record.windowEnd)) return false
        return record.completedAt?.let { !now.isBefore(it.plus(replacementCooldown)) } ?: true
    }

    private fun finish(record: FrontierSiegeRecord, now: Instant) {
        defeat(record)
        store.save(record.copy(completedAt = now))
    }
}
