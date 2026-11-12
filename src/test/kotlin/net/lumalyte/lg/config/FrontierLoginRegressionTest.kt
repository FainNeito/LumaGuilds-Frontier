package net.lumalyte.lg.config

import io.mockk.*
import net.lumalyte.lg.interaction.listeners.FrontierServerRules
import org.bukkit.Location
import org.bukkit.entity.Player
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.spigotmc.event.player.PlayerSpawnLocationEvent
import kotlin.test.assertEquals

class FrontierLoginRegressionTest {
    @Test fun `loaded returning player is not teleported on join`() {
        val player = mockk<Player>(relaxed=true)
        every { player.hasPlayedBefore() } returns true
        FrontierServerRules(FrontierConfig(enabled=true)).onFirstJoin(org.bukkit.event.player.PlayerJoinEvent(player, null as net.kyori.adventure.text.Component?))
        verify(exactly=0) { player.teleport(any<Location>()) }
    }

    @Test fun `legacy configuration player without loaded history keeps saved location`() {
        val server = MockBukkit.mock()
        try {
            val world = server.addSimpleWorld("frontier")
            val player = mockk<Player>(relaxed=true)
            every { player.hasPlayedBefore() } returns false
            val saved = Location(world, 900.5, 70.0, -840.5, 42f, 12f)
            val event = PlayerSpawnLocationEvent(player, saved)
            val rules = spyk(FrontierServerRules(FrontierConfig(enabled=true)), recordPrivateCalls=true)
            every { rules["spawn"]() } returns Location(world, 50.5, 65.0, 0.5)
            rules.onFirstSpawn(event)
            assertEquals(saved, event.spawnLocation)
        } finally { MockBukkit.unmock(); unmockkAll() }
    }
}
