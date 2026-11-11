package net.lumalyte.lg.domain.values

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Daily wall-clock start with a fixed elapsed duration, reconstructed without scheduler state. */
data class FrontierRaidSchedule(
    val zone: ZoneId,
    val start: LocalTime,
    val duration: Duration,
) {
    init {
        require(!duration.isNegative && !duration.isZero && duration <= Duration.ofHours(12)) {
            "Raid duration must be greater than zero and at most 12 hours"
        }
    }

    /** Start is inclusive; end is exclusive. Java time resolves DST gaps forward and overlaps earlier. */
    fun stateAt(now: Instant): FrontierRaidState {
        val date = now.atZone(zone).toLocalDate()
        val starts = (-1L..1L).map { date.plusDays(it).atTime(start).atZone(zone).toInstant() }
        val active = starts.lastOrNull { !now.isBefore(it) && now.isBefore(it.plus(duration)) }
        return if (active != null) FrontierRaidState(true, active.plus(duration))
        else FrontierRaidState(false, starts.first { it.isAfter(now) })
    }
}

/** nextTransition is the end of the active raid, or the next raid's start. */
data class FrontierRaidState(val active: Boolean, val nextTransition: Instant)
