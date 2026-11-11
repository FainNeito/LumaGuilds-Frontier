package net.lumalyte.lg.application.services

import io.mockk.*
import net.lumalyte.lg.application.persistence.*
import net.lumalyte.lg.config.MainConfig
import net.lumalyte.lg.domain.entities.*
import net.lumalyte.lg.domain.values.*
import net.lumalyte.lg.infrastructure.services.FrontierObjectives
import org.bukkit.Material
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class FrontierFoundationMigrationTest {
    @Test fun `legacy solo bells consolidate into one named vault without losing chunk ownership`() {
        val server=MockBukkit.mock()
        try {
            val world=server.addSimpleWorld("frontier"); val player=UUID.randomUUID()
            val first=Claim(world.uid,player,null,Position3D(0,64,0),"Founding")
            val second=Claim(world.uid,player,null,Position3D(16,64,0),"Outpost").copy(creationTime=first.creationTime.plusSeconds(1))
            world.getBlockAt(0,64,0).type=Material.BELL; world.getBlockAt(16,64,0).type=Material.BELL
            val records=mutableMapOf(first.id to first,second.id to second)
            val p1=Partition(first.id,Area(Position2D(0,0),Position2D(15,15))); val p2=Partition(second.id,Area(Position2D(16,0),Position2D(31,15)))
            val land=mutableMapOf(p1.id to p1,p2.id to p2)
            val claims=mockk<ClaimRepository>()
            every { claims.getAll() } answers { records.values.toSet() }
            every { claims.getByPlayer(player) } answers { records.values.toSet() }
            every { claims.update(any()) } answers { val c=firstArg<Claim>(); records[c.id]=c; true }
            val partitions=mockk<PartitionRepository>()
            every { partitions.getByClaim(any()) } answers { land.values.filter { it.claimId==firstArg<UUID>() }.toSet() }
            every { partitions.update(any()) } answers { val p=firstArg<Partition>(); land[p.id]=p; true }
            val release=mockk<FrontierClaimRelease>()
            every { release.release(any<FrontierReleasePlan>()) } answers {
                val plan=firstArg<FrontierReleasePlan>()
                plan.claimIds.forEach { id -> records.remove(id); land.entries.removeIf { it.value.claimId==id } }
                plan
            }
            val service=FrontierObjectives(MainConfig(),mockk(relaxed=true),release,claims,mockk(),partitions) { mockk() }
            service.migrateFoundations(); service.migrateFoundations()
            assertEquals(Material.BARREL,world.getBlockAt(0,64,0).type)
            assertEquals(Material.AIR,world.getBlockAt(16,64,0).type)
            assertEquals(setOf(first.id),records.keys)
            assertEquals(2,land.size); assertTrue(land.values.all { it.claimId==first.id })
            assertEquals("Founding",records[first.id]?.name)
        } finally { MockBukkit.unmock(); unmockkAll() }
    }
    @Test fun `already packed guild cannot retain unraidable land after update`() {
        val claims=mockk<ClaimRepository>(); val guilds=mockk<GuildRepository>()
        val guild=Guild(UUID.randomUUID(),"Packed",createdAt=Instant.now(),vaultStatus=VaultStatus.UNAVAILABLE)
        val claim=Claim(UUID.randomUUID(),UUID.randomUUID(),guild.id,Position3D(0,64,0),"Land")
        every { claims.getAll() } returns setOf(claim)
        every { claims.getByTeam(guild.id) } returns setOf(claim)
        every { guilds.getById(guild.id) } returns guild
        val release=mockk<FrontierClaimRelease>(relaxed=true)
        val server=MockBukkit.mock()
        try {
            FrontierObjectives(MainConfig(),mockk(relaxed=true),release,claims,guilds,mockk()) { mockk() }.migrateFoundations()
            verify(exactly=1) { release.release(FrontierOwner(FrontierOwnerType.GUILD,guild.id)) }
        } finally { MockBukkit.unmock() }
    }
}
