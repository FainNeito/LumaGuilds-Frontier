package net.lumalyte.lg.interaction.menus.guild

import org.junit.jupiter.api.Test
import kotlin.test.*

class FrontierDialogCapabilityTest {
    @Test fun `only known clients with dialog support pass`() {
        assertFalse(FrontierDialogCapability.supported(null))
        assertFalse(FrontierDialogCapability.supported(770))
        assertTrue(FrontierDialogCapability.supported(771))
        assertTrue(FrontierDialogCapability.supported(1073742170))
    }
}
