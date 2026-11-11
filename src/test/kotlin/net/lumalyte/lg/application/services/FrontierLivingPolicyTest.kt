package net.lumalyte.lg.application.services

import kotlin.test.*

class FrontierLivingPolicyTest {
    @Test fun `new territory receives grace instead of expiring immediately`() {
        assertFalse(FrontierLivingPolicy.expired(null, 1000000, 1000000, 100, false))
    }
    @Test fun `online defenders prevent expiry even beyond deadline`() {
        assertFalse(FrontierLivingPolicy.expired(0, 0, 101, 100, true))
        assertTrue(FrontierLivingPolicy.expired(0, 0, 100, 100, false))
    }
    @Test fun `current member recent activity resets deadline`() {
        assertFalse(FrontierLivingPolicy.expired(95, 0, 101, 100, false))
    }
    @Test fun `clock rollback does not expire claims`() {
        assertFalse(FrontierLivingPolicy.expired(200, 100, 50, 100, false))
    }
    @Test fun `caps cannot increase damage`() {
        assertEquals(16.0, FrontierLivingPolicy.capDamage(100.0, 16.0))
        assertEquals(3.0, FrontierLivingPolicy.capDamage(3.0, 16.0))
    }
    @Test fun `nearby requires same world and respects radius boundary`() {
        assertTrue(FrontierLivingPolicy.nearby(true, 40000.0, 200.0))
        assertFalse(FrontierLivingPolicy.nearby(false, 0.0, 200.0))
        assertFalse(FrontierLivingPolicy.nearby(true, 40001.0, 200.0))
    }
}
