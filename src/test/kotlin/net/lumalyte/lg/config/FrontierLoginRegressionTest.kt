package net.lumalyte.lg.config

import io.mockk.*
import net.lumalyte.lg.interaction.listeners.FrontierServerRules
import org.bukkit.Location
import org.bukkit.entity.Player
import org.junit.jupiter.api.Test

class FrontierLoginRegressionTest {
    @Test fun `loaded returning player is not teleported on join`() {
        val player = mockk<Player>(relaxed=true)
        every { player.hasPlayedBefore() } returns true
        FrontierServerRules(FrontierConfig(enabled=true)).onFirstJoin(org.bukkit.event.player.PlayerJoinEvent(player, null as net.kyori.adventure.text.Component?))
        verify(exactly=0) { player.teleport(any<Location>()) }
    }

    @Test fun `returning player on nether roof is moved to safe spawn on join`() {
        val world = mockk<org.bukkit.World>()
        every { world.environment } returns org.bukkit.World.Environment.NETHER
        val player = mockk<Player>(relaxed=true)
        every { player.hasPlayedBefore() } returns true
        every { player.location } returns Location(world, 25.0, 129.0, 25.0)
        val safe = Location(world, 10.0, 65.0, 10.0)
        val rules = spyk(FrontierServerRules(FrontierConfig(enabled=true)), recordPrivateCalls=true)
        every { rules["spawn"]() } returns safe
        rules.onFirstJoin(org.bukkit.event.player.PlayerJoinEvent(player, null as net.kyori.adventure.text.Component?))
        verify(exactly=1) { player.teleport(safe) }
    }

}
