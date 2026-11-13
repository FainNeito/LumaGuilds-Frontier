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
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Powerable
import org.bukkit.block.data.type.RedstoneWire
import org.bukkit.Material
import org.bukkit.event.Event
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import net.lumalyte.lg.domain.values.FrontierRaidSchedule
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.plugin.java.JavaPlugin

class FrontierTntArmingTest {
    @Test fun `conflicting automatic control and manual arming do not assign wrong player`() {
        val registry=FrontierTntArmingRegistry(); val key=FrontierTntBlock(UUID.randomUUID(),1,64,2)
        val first=UUID.randomUUID(); val second=UUID.randomUUID()
        assertTrue(registry.arm(key,first,1000,60000))
        assertFalse(registry.armAutomatic(key,second,2000,60000))
        assertNull(registry.consume(key,2001))
    }
    @Test fun `operating a connected lever attributes only connected TNT`() {
        val worldId=UUID.randomUUID(); val playerId=UUID.randomUUID(); val world=mockk<World>()
        every { world.uid } returns worldId
        every { world.isChunkLoaded(any<Int>(),any<Int>()) } returns true
        val blocks=mutableMapOf<Triple<Int,Int,Int>,Block>()
        fun block(x:Int,y:Int,z:Int,type:Material,data:org.bukkit.block.data.BlockData): Block {
            val value=mockk<Block>()
            every { value.world } returns world; every { value.x } returns x; every { value.y } returns y; every { value.z } returns z
            every { value.type } returns type; every { value.blockData } returns data
            blocks[Triple(x,y,z)]=value; return value
        }
        val air=mockk<org.bukkit.block.data.BlockData>()
        every { world.getBlockAt(any<Int>(),any<Int>(),any<Int>()) } answers {
            val x=firstArg<Int>(); val y=secondArg<Int>(); val z=thirdArg<Int>()
            blocks[Triple(x,y,z)] ?: block(x,y,z,Material.AIR,air)
        }
        val power=mockk<Powerable> { every { isPowered } returns false }
        val lever=block(0,64,0,Material.LEVER,power)
        val wire=mockk<RedstoneWire> {
            every { getFace(any()) } returns RedstoneWire.Connection.NONE
            every { getFace(BlockFace.WEST) } returns RedstoneWire.Connection.SIDE
            every { getFace(BlockFace.EAST) } returns RedstoneWire.Connection.SIDE
        }
        block(1,64,0,Material.REDSTONE_WIRE,wire)
        block(2,64,0,Material.TNT,air)
        block(0,64,2,Material.TNT,air)
        val player=mockk<Player> { every { uniqueId } returns playerId }
        val event=mockk<PlayerInteractEvent> {
            every { action } returns Action.RIGHT_CLICK_BLOCK
            every { clickedBlock } returns lever
            every { useInteractedBlock() } returns Event.Result.ALLOW
            every { this@mockk.player } returns player
        }
        val schedule=FrontierRaidSchedule(ZoneId.of("UTC"),LocalTime.now(ZoneId.of("UTC")).minusMinutes(1),Duration.ofHours(2))
        val registry=FrontierTntArmingRegistry()
        FrontierTntArming(mockk(),FrontierConfig(schedule=schedule),registry).activate(event)
        assertEquals(playerId,registry.consume(FrontierTntBlock(worldId,2,64,0),System.currentTimeMillis()))
        assertNull(registry.consume(FrontierTntBlock(worldId,0,64,2),System.currentTimeMillis()))
    }
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
