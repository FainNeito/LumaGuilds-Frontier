package net.lumalyte.lg.domain.values

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrontierRaidScheduleTest {
    private fun schedule(time: String, zone: String = "UTC") = FrontierRaidSchedule(
        ZoneId.of(zone), LocalTime.parse(time), Duration.ofHours(1),
    )

    @Test fun `start is inclusive and end is exclusive`() {
        val schedule = schedule("20:00")
        assertFalse(schedule.stateAt(Instant.parse("2026-11-09T19:59:59Z")).active)
        assertTrue(schedule.stateAt(Instant.parse("2026-11-09T20:00:00Z")).active)
        val ended = schedule.stateAt(Instant.parse("2026-11-09T21:00:00Z"))
        assertFalse(ended.active)
        assertEquals(Instant.parse("2026-11-10T20:00:00Z"), ended.nextTransition)
    }

    @Test fun `restart after midnight recovers previous days active window`() {
        val state = schedule("23:30").stateAt(Instant.parse("2026-11-10T00:15:00Z"))
        assertTrue(state.active)
        assertEquals(Instant.parse("2026-11-10T00:30:00Z"), state.nextTransition)
    }

    @Test fun `spring gap shifts start forward without extending elapsed duration`() {
        val state = schedule("02:30", "America/New_York")
            .stateAt(Instant.parse("2026-03-08T07:45:00Z"))
        assertTrue(state.active)
        assertEquals(Instant.parse("2026-03-08T08:30:00Z"), state.nextTransition)
    }

    @Test fun `fall overlap does not open a second window`() {
        val state = schedule("01:30", "America/New_York")
            .stateAt(Instant.parse("2026-11-01T06:30:00Z"))
        assertFalse(state.active)
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"), state.nextTransition)
    }
}
