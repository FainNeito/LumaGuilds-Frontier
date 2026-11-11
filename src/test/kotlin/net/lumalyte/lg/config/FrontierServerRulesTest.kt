package net.lumalyte.lg.config

import io.mockk.*
import net.lumalyte.lg.domain.values.Area
import net.lumalyte.lg.domain.values.Position2D
import net.lumalyte.lg.infrastructure.services.ConfigServiceBukkit
import net.lumalyte.lg.interaction.listeners.FrontierServerRules
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.entity.EntityToggleGlideEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.*

class FrontierServerRulesTest {
    @Test fun `withers cannot spawn and loaded withers are removed without touching other mobs`() {
        val rules=FrontierServerRules(FrontierConfig(enabled=true))
        val wither=mockk<org.bukkit.entity.Wither>(relaxed=true)
        val cow=mockk<org.bukkit.entity.Cow>(relaxed=true)
        val spawn=org.bukkit.event.entity.CreatureSpawnEvent(wither,org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.BUILD_WITHER)
        rules.onWitherSpawn(spawn)
        assertTrue(spawn.isCancelled)
        rules.removeWithers(listOf(wither,cow))
        verify(exactly=1) { wither.remove() }
        verify(exactly=0) { cow.remove() }
    }

    @Test fun `war banner grant and rally commands reach the permission checked command handler`() {
        val player = mockk<org.bukkit.entity.Player>(relaxed = true)
        val open = mockk<(org.bukkit.entity.Player) -> Unit>(relaxed = true)
        val rules = FrontierServerRules(FrontierConfig(enabled = true), open)
        for (root in listOf("guild", "g", "guilds", "lumaguilds:g")) {
            for (suffix in listOf("warbanner", "warbanner tp")) {
                val event = PlayerCommandPreprocessEvent(player, "/$root $suffix")
                rules.onCommand(event)
                assertFalse(event.isCancelled, event.message)
            }
        }
        verify(exactly = 0) { open(any()) }
    }

    @Test fun `test profile parses configurable siege and rejects invalid values`() {
        val yaml = YamlConfiguration()
        yaml.set("frontier.enabled", true)
        yaml.set("claim_geometry", "CHUNKS")
        yaml.set("frontier.siege.required_hits", 3)
        yaml.set("frontier.siege.replacement_cooldown_minutes", 1)
        val config = ConfigServiceBukkit(yaml).loadConfig().frontier
        assertEquals(3, config.requiredHits)
        assertEquals(Duration.ofMinutes(1), config.replacementCooldown)
        yaml.set("frontier.siege.required_hits", 0)
        assertFailsWith<IllegalArgumentException> { ConfigServiceBukkit(yaml).loadConfig() }
    }

    @Test fun `reserved spawn and partial border chunks cannot become land`() {
        val config = FrontierConfig(enabled = true, reservedSpawnRadius = 192)
        fun area(x: Int, z: Int) = Area(Position2D(x, z), Position2D(x + 15, z + 15))
        assertFalse(config.permitsLand(area(0, 0)))
        assertFalse(config.permitsLand(area(192, 192)))
        assertFalse(config.permitsLand(area(2496, 256)))
        assertFalse(config.permitsLand(area(-2512, 256)))
        assertTrue(config.permitsLand(area(-2496, 256)))
        assertTrue(config.permitsLand(area(208, 0)))
        assertTrue(FrontierConfig().permitsLand(area(0, 0)))
    }

    @Test fun `guild progression commands are blocked through aliases while membership remains available`() {
        val player = mockk<org.bukkit.entity.Player>(relaxed = true)
        val rules = FrontierServerRules(FrontierConfig(enabled = true))
        for (command in listOf("/g home", "/lumaguilds:g bank", "/guild quests")) {
            val event = PlayerCommandPreprocessEvent(player, command)
            rules.onCommand(event)
            assertTrue(event.isCancelled, command)
        }
        val invite = PlayerCommandPreprocessEvent(player, "/g invite Player")
        rules.onCommand(invite)
        assertFalse(invite.isCancelled)
        assertFalse(Material.TOTEM_OF_UNDYING in FrontierServerRules.bannedItems)
        assertTrue(Material.MACE in FrontierServerRules.bannedItems)
        val glide = mockk<EntityToggleGlideEvent>(relaxed = true) { every { isGliding } returns true }
        rules.onGlide(glide)
        verify { glide.isCancelled = true }
    }

    @Test fun `menu aliases and onboarding open frontier menu without legacy dispatch`() {
        val player = mockk<org.bukkit.entity.Player>(relaxed = true)
        every { player.hasPermission("lumaguilds.guild.menu") } returns true
        val open = mockk<(org.bukkit.entity.Player) -> Unit>(relaxed = true)
        val rules = FrontierServerRules(FrontierConfig(enabled = true), open)
        val commands = listOf("/guild", "/g", "/guild menu", "/lumaguilds:g menu", "/g help", "/g start ignored")
        commands.forEach {
            val event = PlayerCommandPreprocessEvent(player, it)
            rules.onCommand(event)
            assertTrue(event.isCancelled)
        }
        verify(exactly = commands.size) { open(player) }
        every { player.hasPermission("lumaguilds.guild.menu") } returns false
        rules.onCommand(PlayerCommandPreprocessEvent(player, "/guild"))
        verify(exactly = commands.size) { open(player) }
    }

    @Test fun `raid countdown uses hours instead of thousands of minutes`() {
        assertEquals("21h 16m 46s", net.lumalyte.lg.interaction.listeners.FrontierTheme.duration(76606))
        assertEquals("0m 09s", net.lumalyte.lg.interaction.listeners.FrontierTheme.duration(9))
    }
    @Test fun `spawn reservation is disabled by default and configurable independently of spawn`() {
        val area = Area(Position2D(0, 0), Position2D(15, 15))
        assertTrue(FrontierConfig(enabled = true).permitsLand(area))
        assertFalse(FrontierConfig(enabled = true, reservedSpawnRadius = 32).permitsLand(area))
        val yaml = YamlConfiguration()
        yaml.set("frontier.enabled", true)
        yaml.set("claim_geometry", "CHUNKS")
        yaml.set("frontier.claims.reserved_spawn_radius", 64)
        yaml.set("frontier.claims.entry_titles", false)
        val config = ConfigServiceBukkit(yaml).loadConfig().frontier
        assertEquals(64, config.reservedSpawnRadius)
        assertFalse(config.claimTitles)
    }
}
