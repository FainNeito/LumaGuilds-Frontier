package net.lumalyte.lg.config

import net.lumalyte.lg.interaction.listeners.FrontierServerRules
import org.bukkit.configuration.file.YamlConfiguration
import net.lumalyte.lg.infrastructure.services.ConfigServiceBukkit
import org.junit.jupiter.api.Test
import kotlin.test.*

class FrontierMenuAccessRegressionTest {
    @Test fun `retired onboarding commands do not open the Frontier menu`() {
        val player = io.mockk.mockk<org.bukkit.entity.Player>(relaxed = true)
        io.mockk.every { player.hasPermission(any<String>()) } returns true
        var opened = false
        val rules = FrontierServerRules(FrontierConfig(enabled = true)) { opened = true }
        for (root in listOf("g", "guild", "guilds", "lumaguilds:g")) {
            for (command in listOf("start", "gettingstarted", "start dismiss")) {
                val event = org.bukkit.event.player.PlayerCommandPreprocessEvent(player, "/$root $command")
                rules.onCommand(event)
                assertTrue(event.isCancelled)
                assertFalse(opened, "Retired command must not open the menu: ${event.message}")
                assertFalse(command.substringBefore(' ') in FrontierServerRules.guildCommands)
            }
        }
    }
    @Test fun `guild filter permits vault and ally chat`() {
        assertTrue("vault" in FrontierServerRules.guildCommands)
        assertTrue("allychat" in FrontierServerRules.guildCommands)
    }
    @Test fun `reservation optionally expands to all random spawn chunks`() {
        val yaml = YamlConfiguration()
        yaml.set("frontier.enabled", true)
        yaml.set("claim_geometry", "CHUNKS")
        yaml.set("frontier.world.spawn_max_radius", 200)
        yaml.set("frontier.server_territory.cover_spawn_radius", true)
        val zone = ConfigServiceBukkit(yaml).loadConfig().frontier.serverTerritory!!
        for (x in listOf(-200, 199)) assertTrue(zone.contains(x, 0))
        for (z in listOf(-200, 199)) assertTrue(zone.contains(0, z))
        assertFalse(zone.contains(208, 0))
        yaml.set("frontier.server_territory.enabled", false)
        assertNull(ConfigServiceBukkit(yaml).loadConfig().frontier.serverTerritory)
    }
}
