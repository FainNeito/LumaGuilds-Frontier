package net.lumalyte.lg.interaction.listeners

import io.mockk.*
import org.bukkit.entity.Player
import org.bukkit.entity.Horse
import org.bukkit.entity.minecart.ExplosiveMinecart
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerRiptideEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.Material
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.*

class FrontierLivingWorldTest {
    private fun scenario(body: (FrontierLivingWorld) -> Unit) {
        MockBukkit.mock()
        try {
            val plugin = MockBukkit.createMockPlugin()
            plugin.dataFolder.mkdirs()
            plugin.dataFolder.resolve("frontier-living-world.yml").writeText("discord:\n  enabled: false\n")
            body(FrontierLivingWorld(plugin))
        } finally { MockBukkit.unmock(); unmockkAll() }
    }
    @Test fun `riptide cooldown follows player across tridents but not underwater`() = scenario { listener ->
        val server = MockBukkit.getMock()!!
        val world = server.addSimpleWorld("frontier")
        val player = server.addPlayer()
        player.teleport(org.bukkit.Location(world,0.0,100.0,0.0))
        val first = mockk<PlayerRiptideEvent>(relaxed = true) { every { getPlayer() } returns player }
        listener.usedRiptide(first)
        val next = mockk<PlayerRiptideEvent>(relaxed = true) { every { getPlayer() } returns player }
        listener.denyRiptide(next)
        verify { next.isCancelled = true }
        player.teleport(org.bukkit.Location(world,0.0,20.0,0.0))
        val below = mockk<PlayerRiptideEvent>(relaxed = true) { every { getPlayer() } returns player }
        listener.denyRiptide(below)
        verify(exactly = 0) { below.isCancelled = true }
    }
    @Test fun `minecart cap applies to players without affecting objective explosions`() = scenario { listener ->
        val player = mockk<Player>(relaxed = true)
        val cart = mockk<ExplosiveMinecart>(relaxed = true)
        val event = mockk<EntityDamageByEntityEvent>(relaxed = true) {
            every { entity } returns player; every { damager } returns cart
            every { damage } returns 80.0
        }
        listener.damage(event)
        verify { event.damage = 20.0 }
    }
    @Test fun `horse damage remains vanilla even with armor`() = scenario { listener ->
        val horse = mockk<Horse>(relaxed = true)
        every { horse.inventory.armor } returns ItemStack(Material.IRON_HORSE_ARMOR)
        val event = mockk<EntityDamageByEntityEvent>(relaxed = true) {
            every { entity } returns horse; every { damage } returns 10.0
        }
        listener.damage(event)
        verify(exactly = 0) { event.damage = any() }
        clearMocks(event, answers = false)
        every { horse.inventory.armor } returns null
        listener.damage(event)
        verify(exactly = 0) { event.damage = any() }
    }
}
