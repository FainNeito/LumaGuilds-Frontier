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
    @Test fun `Ender Chest crafting placement and access are blocked while regular chests open`() {
        val rules = FrontierServerRules(FrontierConfig(enabled = true))
        val craft = mockk<org.bukkit.event.inventory.CraftItemEvent>(relaxed = true) {
            every { recipe.result.type } returns Material.ENDER_CHEST
        }
        rules.onCraft(craft)
        verify { craft.isCancelled = true }
        val place = mockk<org.bukkit.event.block.BlockPlaceEvent>(relaxed = true) {
            every { block.location.world.environment } returns org.bukkit.World.Environment.NORMAL
            every { itemInHand.type } returns Material.ENDER_CHEST
        }
        rules.onPlace(place)
        verify { place.isCancelled = true }
        val interact = mockk<org.bukkit.event.player.PlayerInteractEvent>(relaxed = true) {
            every { item } returns null
            every { clickedBlock?.type } returns Material.ENDER_CHEST
        }
        rules.onUse(interact)
        verify { interact.isCancelled = true }
        val open = mockk<org.bukkit.event.inventory.InventoryOpenEvent>(relaxed = true) {
            every { inventory.type } returns org.bukkit.event.inventory.InventoryType.ENDER_CHEST
        }
        rules.onOpen(open)
        verify(exactly = 1) { open.isCancelled = true }
        every { open.inventory.type } returns org.bukkit.event.inventory.InventoryType.CHEST
        rules.onOpen(open)
        verify(exactly = 1) { open.isCancelled = true }
    }
    @Test fun `End entry opens at the exact November 27 Eastern boundary`() {
        val end = mockk<org.bukkit.World> { every { environment } returns org.bukkit.World.Environment.THE_END }
        val normal = mockk<org.bukkit.World> { every { environment } returns org.bukkit.World.Environment.NORMAL }
        val before = java.time.Instant.parse("2026-11-27T20:59:59.999Z")
        assertTrue(FrontierServerRules.endLocked(end, before))
        assertFalse(FrontierServerRules.endLocked(normal, before))
        assertFalse(FrontierServerRules.endLocked(end, java.time.Instant.parse("2026-11-27T21:00:00Z")))
    }

    @Test fun `primed TNT and TNT carts cannot hurt players`() {
        val rules = FrontierServerRules(FrontierConfig(enabled = true))
        for (source in listOf(mockk<org.bukkit.entity.TNTPrimed>(), mockk<org.bukkit.entity.minecart.ExplosiveMinecart>())) {
            val event = mockk<org.bukkit.event.entity.EntityDamageByEntityEvent>(relaxed = true) {
                every { damager } returns source
                every { entity } returns mockk<org.bukkit.entity.Player>()
                every { cause } returns org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
            }
            rules.onDamage(event)
            verify { event.isCancelled = true }
        }
    }
    @Test fun `withers cannot spawn and loaded withers are removed without touching other mobs`() {
        val rules=FrontierServerRules(FrontierConfig(enabled=true))
        val wither=mockk<org.bukkit.entity.Wither>(relaxed=true)
        val shulker=mockk<org.bukkit.entity.Shulker>(relaxed=true)
        val cow=mockk<org.bukkit.entity.Cow>(relaxed=true)
        val spawn=org.bukkit.event.entity.CreatureSpawnEvent(wither,org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.BUILD_WITHER)
        rules.onMobSpawn(spawn)
        assertTrue(spawn.isCancelled)
        val shulkerSpawn = org.bukkit.event.entity.CreatureSpawnEvent(shulker, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.NATURAL)
        rules.onMobSpawn(shulkerSpawn)
        assertTrue(shulkerSpawn.isCancelled)
        rules.removeDisabledMobs(listOf(wither,shulker,cow))
        verify(exactly=1) { wither.remove() }
        verify(exactly=1) { shulker.remove() }
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
        assertEquals(17, FrontierServerRules.bannedItems.count { it.name.endsWith("SHULKER_BOX") })
        val glide = mockk<EntityToggleGlideEvent>(relaxed = true) { every { isGliding } returns true }
        rules.onGlide(glide)
        verify { glide.isCancelled = true }
    }

    @Test fun `supported menu aliases open frontier menu without legacy dispatch`() {
        val player = mockk<org.bukkit.entity.Player>(relaxed = true)
        every { player.hasPermission("lumaguilds.guild.menu") } returns true
        val open = mockk<(org.bukkit.entity.Player) -> Unit>(relaxed = true)
        val rules = FrontierServerRules(FrontierConfig(enabled = true), open)
        val commands = listOf("/guild", "/g", "/guild menu", "/lumaguilds:g menu", "/g help")
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
