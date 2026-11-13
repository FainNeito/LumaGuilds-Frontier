package net.lumalyte.lg.interaction.listeners

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*
import io.mockk.*
import net.lumalyte.lg.config.FrontierConfig
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.entity.TNTPrimed
import org.bukkit.event.block.TNTPrimeEvent
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.plugin.java.JavaPlugin

class FrontierTntArmingTest {
    @Test fun `armed blocks keep the first owner until expiry and are consumed once`() {
        val registry = FrontierTntArmingRegistry()
        val key = FrontierTntBlock(UUID.randomUUID(), 1, 64, 2)
        val first = UUID.randomUUID(); val other = UUID.randomUUID()
        assertTrue(registry.arm(key, first, 1000, 60_000))
        assertFalse(registry.arm(key, other, 2000, 60_000))
        assertEquals(first, registry.consume(key, 2001))
        assertNull(registry.consume(key, 2002))
        assertTrue(registry.arm(key, other, 2003, 100))
        assertNull(registry.consume(key, 2103))
    }
    @Test fun `identical coordinates in other worlds cannot claim attribution`() {
        val registry = FrontierTntArmingRegistry()
        val key = FrontierTntBlock(UUID.randomUUID(), 1, 64, 2)
        val player = UUID.randomUUID()
        assertTrue(registry.arm(key, player, 0, 1000))
        assertNull(registry.consume(key.copy(world = UUID.randomUUID()), 1))
        assertEquals(player, registry.consume(key, 1))
    }
    @Test fun `an explosion carries its player source into the newly spawned TNT`() {
        val world=mockk<World> { every { uid } returns UUID.fromString("00000000-0000-0000-0000-000000000001") }
        val block=mockk<Block> { every { this@mockk.world } returns world; every { x } returns 8; every { y } returns 64; every { z } returns 9 }
        val player=mockk<Player>()
        val parent=mockk<TNTPrimed> { every { source } returns player }
        val child=mockk<TNTPrimed>(relaxed=true) { every { location.block } returns block; every { source } returns null }
        val prime=mockk<TNTPrimeEvent> { every { this@mockk.block } returns block; every { primingEntity } returns parent }
        val spawn=mockk<EntitySpawnEvent> { every { entity } returns child }
        val listener=FrontierTntArming(mockk<JavaPlugin>(),FrontierConfig())
        listener.prime(prime); listener.spawn(spawn)
        verify(exactly=1) { child.source = player }
        listener.spawn(spawn)
        verify(exactly=1) { child.source = player }
    }
    @Test fun `armed redstone priming assigns its owner to the resulting TNT`() {
        val id=UUID.randomUUID(); val playerId=UUID.randomUUID()
        val world=mockk<World> { every { uid } returns id }
        val block=mockk<Block> { every { this@mockk.world } returns world; every { x } returns 8; every { y } returns 64; every { z } returns 9 }
        val player=mockk<Player>()
        val child=mockk<TNTPrimed>(relaxed=true) { every { location.block } returns block; every { source } returns null }
        val prime=mockk<TNTPrimeEvent> { every { this@mockk.block } returns block; every { primingEntity } returns null }
        val registry=FrontierTntArmingRegistry()
        registry.arm(FrontierTntBlock(id,8,64,9),playerId,System.currentTimeMillis(),60_000)
        val listener=FrontierTntArming(mockk<JavaPlugin>(),FrontierConfig(),registry) { if (it==playerId) player else null }
        listener.prime(prime); listener.spawn(mockk<EntitySpawnEvent> { every { entity } returns child })
        verify(exactly=1) { child.source = player }
    }
}
