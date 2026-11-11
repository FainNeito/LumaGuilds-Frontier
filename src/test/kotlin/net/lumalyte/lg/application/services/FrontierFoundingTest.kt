package net.lumalyte.lg.application.services

import io.mockk.*
import net.lumalyte.lg.application.actions.claim.CreateClaim
import net.lumalyte.lg.application.persistence.*
import net.lumalyte.lg.application.results.claim.CreateClaimResult
import net.lumalyte.lg.config.*
import net.lumalyte.lg.domain.entities.*
import net.lumalyte.lg.domain.values.*
import org.junit.jupiter.api.Test
import java.time.*
import java.util.UUID
import kotlin.test.*

class FrontierFoundingTest {
    private val player=UUID.randomUUID(); private val world=UUID.randomUUID()
    private val records=mutableMapOf<UUID,Claim>(); private val land=mutableMapOf<UUID,Partition>()
    private val claims=mockk<ClaimRepository>(relaxed=true)
    private val partitions=mockk<PartitionRepository>(relaxed=true)
    private val worlds=mockk<WorldManipulationService>(relaxed=true)
    private val guilds=mockk<GuildService>(relaxed=true)
    private val config=MainConfig(claimGeometry=ClaimGeometry.CHUNKS,frontier=FrontierConfig(enabled=true,
        schedule=FrontierRaidSchedule(ZoneOffset.UTC,LocalTime.now(ZoneOffset.UTC).plusHours(2),Duration.ofHours(1))))
    init {
        every { claims.getById(any()) } answers { records[firstArg()] }
        every { claims.getByPlayer(any()) } answers { records.values.filter { it.playerId==firstArg<UUID>() }.toSet() }
        every { claims.getByTeam(any()) } answers { records.values.filter { it.teamId==firstArg<UUID>() }.toSet() }
        every { claims.add(any()) } answers { val c=firstArg<Claim>(); records[c.id]=c; true }
        every { claims.update(any()) } answers { val c=firstArg<Claim>(); records[c.id]=c; true }
        every { partitions.getByClaim(any()) } answers { land.values.filter { it.claimId==firstArg<UUID>() }.toSet() }
        every { partitions.getByChunk(any()) } answers { val c=firstArg<Position>(); land.values.filter { Position2D(c.x,c.z) in it.area.getChunks() }.toSet() }
        every { partitions.add(any()) } answers { val p=firstArg<Partition>(); land[p.id]=p; true }
        every { worlds.isClaimWorld(any(),any()) } returns true
        every { worlds.isInsideWorldBorder(any(),any()) } returns true
    }
    private fun create(x:Int,z:Int=0,name:String="Founding",guild:UUID?=null) =
        CreateClaim(claims,partitions,mockk(relaxed=true),worlds,guilds,config).executeFrontier(player,name,Position3D(x,64,z),world,guild)
    @Test fun `one founding record owns contiguous chunks and retains one name`() {
        val first=assertIs<CreateClaimResult.Success>(create(0)).claim
        assertEquals(CreateClaimResult.InvalidLocation,create(32))
        assertEquals(CreateClaimResult.InvalidLocation,create(16,16))
        val second=assertIs<CreateClaimResult.Success>(create(16,name="Ignored expansion name")).claim
        assertEquals(first.id,second.id); assertEquals("Founding",second.name)
        assertIs<CreateClaimResult.Success>(create(32))
        assertEquals(1,records.size); assertEquals(3,land.size)
        assertEquals(Position3D(0,64,0),records[first.id]!!.position)
    }
    @Test fun `guild foundation is owned by guild immediately and obeys guild membership`() {
        val guild=Guild(UUID.randomUUID(),"Keepers",createdAt=Instant.now())
        assertEquals(CreateClaimResult.InvalidLocation,create(0,guild=guild.id))
        every { guilds.getPlayerGuilds(player) } returns setOf(guild)
        assertEquals(guild.id,assertIs<CreateClaimResult.Success>(create(0,guild=guild.id)).claim.teamId)
        assertIs<CreateClaimResult.Success>(create(16,guild=guild.id))
    }
    @Test fun `legacy disconnected outpost cannot extend and rename applies throughout owner territory`() {
        val root=assertIs<CreateClaimResult.Success>(create(0)).claim
        val remote=Claim(world,player,null,Position3D(160,64,0),"Old name").copy(creationTime=root.creationTime.plusSeconds(1))
        records[remote.id]=remote; val p=Partition(remote.id,Area(Position2D(160,0),Position2D(175,15))); land[p.id]=p
        val territory=FrontierTerritory(claims,partitions); val owner=FrontierOwner(FrontierOwnerType.PERSONAL,player)
        assertFalse(territory.canExtend(owner,world,Position2D(11,0)))
        assertTrue(territory.rename(owner,"Ashen Keep"))
        assertEquals(setOf("Ashen Keep"),records.values.map { it.name }.toSet())
        assertEquals(root.id,territory.foundation(owner)?.id)
    }
}
