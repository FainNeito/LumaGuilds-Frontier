package net.lumalyte.lg.config

import net.lumalyte.lg.interaction.listeners.FrontierServerRules
import org.bukkit.configuration.file.YamlConfiguration
import net.lumalyte.lg.infrastructure.services.ConfigServiceBukkit
import org.junit.jupiter.api.Test
import kotlin.test.*

class FrontierMenuAccessRegressionTest {
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
