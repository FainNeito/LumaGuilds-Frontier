package net.lumalyte.lg.application.services

import io.mockk.*
import net.lumalyte.lg.application.actions.claim.*
import net.lumalyte.lg.application.persistence.*
import net.lumalyte.lg.application.results.claim.*
import net.lumalyte.lg.config.*
import net.lumalyte.lg.domain.entities.*
import net.lumalyte.lg.domain.values.*
import net.lumalyte.lg.infrastructure.services.FrontierObjectives
import net.lumalyte.lg.interaction.listeners.FrontierClaimExperience
import org.bukkit.*
import org.bukkit.entity.Player
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import java.time.*
import java.util.UUID
import kotlin.test.*

class FrontierClaimExperienceTest {
    private val safe = Instant.parse("2026-11-10T12:00:00Z")
    private fun config() = MainConfig(frontier = FrontierConfig(enabled=true))
    private fun scenario(result: CreateClaimResult? = null, action: (FrontierClaimExperience,Player,Location,CreateClaim)->Unit) {
        val server=MockBukkit.mock()
        try {
            val plugin=MockBukkit.createMockPlugin()
            net.lumalyte.lg.common.PluginKeys.initialize(plugin)
            val world=server.addSimpleWorld("frontier")
            val player=server.addPlayer(); player.isOp=true
            val location=Location(world,0.0,65.0,0.0)
            location.block.getRelative(0,-1,0).type=Material.STONE
            player.teleport(location)
            val create=mockk<CreateClaim>()
            every { create.executeFrontier(any(),any(),any(),any(),any()) } returns (result ?: CreateClaimResult.Success(Claim(world.uid,player.uniqueId,null,Position3D(0,65,0),"Ashen Keep")))
            val valid=mockk<IsNewClaimLocationValid>()
            every { valid.execute(any(),any(),any(),any()) } returns IsNewClaimLocationValidResult.Valid
            val objectives=mockk<FrontierObjectives>(relaxed=true)
            every { objectives.siege.canReplace(any(),any()) } returns true
            val experience=FrontierClaimExperience(plugin,config(),create,valid,mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),objectives) { safe }
            action(experience,player,location,create)
        } finally { MockBukkit.unmock(); unmockkAll() }
    }
    @Test fun `claim preview creates no block and confirmation creates exactly one solo vault without tools`() = scenario { experience,player,location,create ->
        experience.claimCommand(player,"Ashen Keep")
        assertEquals(Material.AIR,location.block.type)
        verify(exactly=0) { create.executeFrontier(any(),any(),any(),any(),any()) }
        experience.confirm(player)
        assertEquals(Material.BARREL,location.block.type)
        assertEquals(Material.AIR,player.inventory.itemInMainHand.type)
        experience.confirm(player)
        verify(exactly=1) { create.executeFrontier(player.uniqueId,"Ashen Keep",any(),player.world.uid,null) }
    }
    @Test fun `claim rejection does not place a vault`() = scenario(CreateClaimResult.RaidLocked) { experience,player,location,_ ->
        experience.claimCommand(player,""); experience.confirm(player)
        assertEquals(Material.AIR,location.block.type)
    }
    @Test fun `changed founding location cannot overwrite another block`() = scenario { experience,player,location,create ->
        experience.claimCommand(player,""); location.block.type=Material.CHEST; experience.confirm(player)
        assertEquals(Material.CHEST,location.block.type)
        verify(exactly=0) { create.executeFrontier(any(),any(),any(),any(),any()) }
    }
    @Test fun `packing warns and waits then surrenders all land before removing guild vault`() {
        val server=MockBukkit.mock()
        try {
            val plugin=MockBukkit.createMockPlugin(); net.lumalyte.lg.common.PluginKeys.initialize(plugin)
            val world=server.addSimpleWorld("frontier"); val block=world.getBlockAt(0,64,0); block.type=Material.CHEST
            val realPlayer=server.addPlayer()
            val player=mockk<Player>(relaxed=true)
            every { player.uniqueId } returns realPlayer.uniqueId
            every { player.inventory } returns realPlayer.inventory
            every { player.getTargetBlockExact(6) } returns block
            val guild=Guild(UUID.randomUUID(),"Keepers",createdAt=safe)
            val vaults=mockk<GuildVaultService>()
            every { vaults.getGuildForVaultChest(any()) } returns guild
            every { vaults.removeVaultChest(guild,false) } returns VaultResult.Success(guild)
            val members=mockk<MemberService>()
            every { members.hasPermission(any(),guild.id,any()) } returns true
            val objectives=mockk<FrontierObjectives>(relaxed=true)
            every { objectives.siege.canReplace(any(),any()) } returns true
            val claims=mockk<ClaimRepository>(relaxed=true)
            val claim=Claim(world.uid,player.uniqueId,guild.id,Position3D(0,64,0),"Keep")
            every { claims.getByTeam(guild.id) } returns setOf(claim)
            val partitions=mockk<PartitionRepository>()
            every { partitions.getByClaim(claim.id) } returns setOf(Partition(claim.id,Area(Position2D(0,0),Position2D(31,15))))
            var now=safe
            val experience=FrontierClaimExperience(plugin,config(),mockk(),mockk(),claims,partitions,mockk(),vaults,members,objectives) { now }
            experience.pickup(player,true)
            verify(exactly=0) { objectives.surrender(any()) }
            experience.pickup(player,false); experience.pickup(player,true)
            verify(exactly=0) { vaults.removeVaultChest(any(),any()) }
            now=now.plusSeconds(3)
            experience.pickup(player,true)
            verifyOrder { objectives.surrender(FrontierOwner(FrontierOwnerType.GUILD,guild.id)); vaults.removeVaultChest(guild,false) }
            assertTrue(realPlayer.inventory.contents.filterNotNull().any { it.type==Material.CHEST })
            val checkedSiege = objectives.siege
            verify(exactly=0) { checkedSiege.hit(any(),any(),any(),any(),any()) }
            now=Instant.parse("2026-11-10T20:00:00Z")
            experience.pickup(player,false); now=now.plusSeconds(4); experience.pickup(player,true)
            verify(exactly=1) { objectives.surrender(any()) }
        } finally { MockBukkit.unmock(); unmockkAll() }
    }
}
