package net.lumalyte.lg.application.services

import io.mockk.*
import net.lumalyte.lg.application.persistence.*
import net.lumalyte.lg.config.*
import net.lumalyte.lg.domain.entities.*
import net.lumalyte.lg.domain.values.*
import net.lumalyte.lg.infrastructure.services.FrontierObjectives
import net.lumalyte.lg.interaction.listeners.FrontierObjectiveListener
import org.bukkit.*
import org.bukkit.block.Block
import org.bukkit.entity.*
import org.bukkit.event.entity.*
import org.junit.jupiter.api.Test
import java.time.*
import java.util.UUID
import kotlin.test.*

class FrontierRaidExplosionsTest {
    private val world=mockk<World> { every { uid } returns UUID.randomUUID() }
    private val position=Position3D(0,64,0)
    private val location=Location(world,0.0,64.0,0.0)
    private val block=mockk<Block> {
        every { location } returns this@FrontierRaidExplosionsTest.location
        every { world } returns this@FrontierRaidExplosionsTest.world
        every { type } returns Material.STONE
    }
    private val claim=Claim(world.uid,UUID.randomUUID(),null,position,"Solo")
    private val claims=mockk<ClaimRepository>(relaxed=true)
    private val partitions=mockk<PartitionRepository>()
    private val vaults=mockk<GuildVaultService>(relaxed=true)
    private val objectives=mockk<FrontierObjectives>(relaxed=true)
    private val members=mockk<MemberService>(relaxed=true)
    private val config=MainConfig(frontier=FrontierConfig(enabled=true,schedule=FrontierRaidSchedule(ZoneOffset.UTC,
        LocalTime.now(ZoneOffset.UTC).minusMinutes(1),Duration.ofHours(1))))
    private val plugin=mockk<org.bukkit.plugin.java.JavaPlugin>(relaxed=true)
    private val listener=FrontierObjectiveListener(objectives,claims,vaults,members,plugin,config,partitions)
    init {
        every { vaults.getGuildForVaultChest(any()) } returns null
        every { claims.getById(claim.id) } returns claim
        every { partitions.getByPosition(any()) } returns setOf(Partition(claim.id,Area(Position2D(0,0),Position2D(15,15))))
    }
    private fun explode(entity: Entity): MutableList<Block> {
        val list=mutableListOf(block)
        val event=mockk<EntityExplodeEvent> { every { this@mockk.entity } returns entity; every { blockList() } returns list }
        listener.onEntityExplosion(event)
        return list
    }
    @Test fun `TNT damages ordinary claim blocks during raids`() { assertEquals(listOf(block),explode(mockk<TNTPrimed> { every { source } returns null })) }
    @Test fun `outside raid TNT cannot damage ordinary claims or the vault`() {
        val closedConfig=MainConfig(frontier=FrontierConfig(enabled=true,schedule=FrontierRaidSchedule(ZoneOffset.UTC,
            LocalTime.now(ZoneOffset.UTC).plusHours(2),Duration.ofMinutes(30))))
        val closed=FrontierObjectiveListener(objectives,claims,vaults,members,plugin,closedConfig,partitions)
        val tnt=mockk<TNTPrimed> { every { source } returns null }
        val blocks=mutableListOf(block)
        val event=mockk<EntityExplodeEvent> { every { entity } returns tnt; every { blockList() } returns blocks }
        closed.onEntityExplosion(event)
        assertTrue(blocks.isEmpty())
        val guild=Guild(UUID.randomUUID(),"Keepers",createdAt=Instant.now())
        every { block.type } returns Material.CHEST
        every { vaults.getGuildForVaultChest(location) } returns guild
        blocks.add(block)
        closed.onEntityExplosion(event)
        assertTrue(blocks.isEmpty())
        verify(exactly=0) { objectives.siege.hit(any(),any(),any(),any(),any()) }
    }
    @Test fun `wither and skull explosions cannot damage claimed terrain even during raids`() {
        assertTrue(explode(mockk<Wither>()).isEmpty()); assertTrue(explode(mockk<WitherSkull>()).isEmpty())
        verify(exactly=0) { objectives.siege.hit(any(),any(),any(),any(),any()) }
    }
    @Test fun `wither direct block destruction is cancelled inside a claim`() {
        val event=mockk<EntityChangeBlockEvent>(relaxed=true) { every { entity } returns mockk<Wither>(); every { block } returns this@FrontierRaidExplosionsTest.block }
        listener.onWitherChange(event)
        verify { event.isCancelled=true }
    }
    @Test fun `TNT hits exposed vault through siege instead of vanilla destruction`() {
        val guild=Guild(UUID.randomUUID(),"Keepers",createdAt=Instant.now())
        every { block.type } returns Material.CHEST
        every { vaults.getGuildForVaultChest(location) } returns guild
        every { objectives.siege.hit(any(),any(),false,any(),4) } returns FrontierSiegeResult.Progress(4,20)
        assertTrue(explode(mockk<TNTPrimed> { every { source } returns null }).isEmpty())
        verify(exactly=1) { objectives.siege.hit(FrontierOwner(FrontierOwnerType.GUILD,guild.id),any(),false,any(),4) }
        verify(exactly=0) { vaults.removeVaultChest(any(),any()) }
    }
}
