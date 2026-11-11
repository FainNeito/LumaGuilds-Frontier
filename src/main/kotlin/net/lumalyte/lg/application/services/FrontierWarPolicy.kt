package net.lumalyte.lg.application.services

import net.lumalyte.lg.domain.values.FrontierRaidSchedule
import java.time.Duration
import java.time.Instant

object FrontierWarPolicy {
    fun window(schedule: FrontierRaidSchedule, now: Instant, notice: Duration): Pair<Instant,Instant>? {
        val state=schedule.stateAt(now)
        if(state.active || Duration.between(now,state.nextTransition)<notice) return null
        return state.nextTransition to state.nextTransition.plus(schedule.duration)
    }
    fun active(start: Instant,end: Instant,now: Instant) = !now.isBefore(start) && now.isBefore(end)
}
