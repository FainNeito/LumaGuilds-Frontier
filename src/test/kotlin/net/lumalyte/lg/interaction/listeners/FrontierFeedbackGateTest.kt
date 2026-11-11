package net.lumalyte.lg.interaction.listeners

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrontierFeedbackGateTest {
    @Test fun `multiple raiders share recipient alarm cooldown`() {
        val gate = FrontierFeedbackGate()
        val recipient = UUID.randomUUID()
        assertTrue(gate.allow(recipient, "raid", 30000, true, 1000))
        assertFalse(gate.allow(recipient, "raid", 30000, true, 1500))
        assertTrue(gate.allow(recipient, "raid", 30000, true, 31000))
    }
    @Test fun `defeat overrides alarm but entry cannot overwrite defeat`() {
        val gate = FrontierFeedbackGate()
        val recipient = UUID.randomUUID()
        assertTrue(gate.allow(recipient, "raid", 30000, true, 1000))
        assertTrue(gate.allow(recipient, "defeat", 0, true, 2000))
        assertFalse(gate.allow(recipient, "claim-enter", 5000, false, 3000))
        assertTrue(gate.allow(recipient, "claim-enter", 5000, false, 6000))
    }
    @Test fun `cooldowns are independent per recipient`() {
        val gate = FrontierFeedbackGate()
        assertTrue(gate.allow(UUID.randomUUID(), "visitor", 30000, false, 1000))
        assertTrue(gate.allow(UUID.randomUUID(), "visitor", 30000, false, 1000))
    }
}
