package net.lumalyte.lg.interaction.listeners

import org.junit.jupiter.api.Test
import kotlin.test.*

class FrontierPortalPolicyTest {
    @Test fun `protected land rejects outsiders only outside raids`() {
        assertTrue(FrontierPortalPolicy.block(raiding=false,claimed=true,member=false))
        assertFalse(FrontierPortalPolicy.block(raiding=false,claimed=true,member=true))
        assertFalse(FrontierPortalPolicy.block(raiding=true,claimed=true,member=false))
        assertFalse(FrontierPortalPolicy.block(raiding=false,claimed=false,member=false))
    }
}
