package net.lumalyte.lg.config

import net.lumalyte.lg.domain.values.PlayerActionType
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrontierConfigTest {
    @Test fun `disabled mode never suspends protections`() {
        assertFalse(FrontierConfig().allows(PlayerActionType.BREAK_BLOCK, Instant.parse("2026-11-09T20:30:00Z")))
    }

    @Test fun `access opens at start and closes exactly at end`() {
        val config = FrontierConfig(enabled = true)
        for (action in listOf(PlayerActionType.BREAK_BLOCK, PlayerActionType.OPEN_CONTAINER, PlayerActionType.OPEN_DOOR)) {
            assertFalse(config.allows(action, Instant.parse("2026-11-09T19:59:59Z")))
            assertTrue(config.allows(action, Instant.parse("2026-11-09T20:00:00Z")))
            assertFalse(config.allows(action, Instant.parse("2026-11-09T21:00:00Z")))
        }
    }

    @Test fun `raid window does not implicitly grant explosives permissions`() {
        assertFalse(FrontierConfig(enabled = true).allows(PlayerActionType.PRIME_TNT, Instant.parse("2026-11-09T20:30:00Z")))
    }
}
