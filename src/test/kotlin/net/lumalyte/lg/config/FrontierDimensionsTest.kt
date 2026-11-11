package net.lumalyte.lg.config

import io.mockk.*
import net.lumalyte.lg.interaction.listeners.FrontierServerRules
import org.bukkit.*
import org.bukkit.event.player.PlayerTeleportEvent
import org.junit.jupiter.api.Test
import kotlin.test.*

class FrontierDimensionsTest {
    private val rules = FrontierServerRules(FrontierConfig(enabled = true))
    private fun world(environment: World.Environment) = mockk<World>(relaxed = true) {
        every { getEnvironment() } returns environment
        every { getName() } returns "frontier"
    }
    @Test fun `portal travel uses same coordinates in both directions`() {
        val overworld = world(World.Environment.NORMAL)
        val nether = world(World.Environment.NETHER)
        val there = rules.portalDestination(Location(overworld,800.0,70.0,-640.0),Location(nether,100.0,130.0,-80.0))
        assertEquals(800.0, there.x); assertEquals(-640.0, there.z); assertEquals(120.0, there.y)
        val back = rules.portalDestination(Location(nether,800.0,70.0,-640.0),Location(overworld,6400.0,70.0,-5120.0))
        assertEquals(800.0, back.x); assertEquals(-640.0, back.z)
    }
    @Test fun `end border is small and nether border matches overworld`() {
        val end = world(World.Environment.THE_END); val nether = world(World.Environment.NETHER)
        rules.configure(end); rules.configure(nether)
        verify { end.worldBorder.size = 512.0; nether.worldBorder.size = 5000.0 }
    }
    @Test fun `roof teleport and gateway teleport are denied`() {
        val normal = world(World.Environment.NORMAL); val nether = world(World.Environment.NETHER)
        val roof = PlayerTeleportEvent(mockk(relaxed = true),Location(normal,0.0,64.0,0.0),Location(nether,0.0,128.0,0.0),PlayerTeleportEvent.TeleportCause.ENDER_PEARL)
        rules.onTeleport(roof); assertTrue(roof.isCancelled)
        val gateway = PlayerTeleportEvent(mockk(relaxed = true),Location(normal,0.0,64.0,0.0),Location(normal,0.0,64.0,0.0),PlayerTeleportEvent.TeleportCause.END_GATEWAY)
        rules.onTeleport(gateway); assertTrue(gateway.isCancelled)
    }
}
