package net.lumalyte.lg.application.services

import net.lumalyte.lg.domain.values.FrontierRaidSchedule
import org.junit.jupiter.api.Test
import java.time.*
import kotlin.test.*

class FrontierWarPolicyTest {
    private val schedule=FrontierRaidSchedule(ZoneOffset.UTC,LocalTime.of(20,0),Duration.ofHours(1))
    @Test fun `declarations need advance notice and cannot start during raids`() {
        assertNotNull(FrontierWarPolicy.window(schedule,Instant.parse("2026-11-11T19:00:00Z"),Duration.ofMinutes(15)))
        assertNull(FrontierWarPolicy.window(schedule,Instant.parse("2026-11-11T19:50:00Z"),Duration.ofMinutes(15)))
        assertNull(FrontierWarPolicy.window(schedule,Instant.parse("2026-11-11T20:30:00Z"),Duration.ofMinutes(15)))
    }
    @Test fun `window closes exactly at scheduled end`() {
        val window=FrontierWarPolicy.window(schedule,Instant.parse("2026-11-11T19:00:00Z"),Duration.ZERO)!!
        assertEquals(Instant.parse("2026-11-11T20:00:00Z"),window.first)
        assertEquals(Instant.parse("2026-11-11T21:00:00Z"),window.second)
        assertFalse(FrontierWarPolicy.active(window.first,window.second,window.second))
        assertTrue(FrontierWarPolicy.active(window.first,window.second,window.first))
    }
}
