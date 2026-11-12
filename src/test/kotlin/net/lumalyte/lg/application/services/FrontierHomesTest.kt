package net.lumalyte.lg.application.services

import io.mockk.*
import net.lumalyte.lg.application.persistence.*
import net.lumalyte.lg.config.FrontierConfig
import net.lumalyte.lg.domain.entities.*
import net.lumalyte.lg.domain.values.*
import net.lumalyte.lg.interaction.listeners.FrontierHomes
import org.bukkit.*
import org.bukkit.event.entity.EntityDamageEvent
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class FrontierHomesTest {
    @Test fun `legacy home loads before its world and resolves after world becomes available`() {
        val server=MockBukkit.mock()
        try {
            val plugin=MockBukkit.createMockPlugin()
            plugin.dataFolder.mkdirs()
            plugin.dataFolder.resolve("frontier-homes.yml").writeText("""
                GUILD:
                  saved:
                    location:
                      ==: org.bukkit.Location
                      world: late-frontier
                      x: 12.5
                      y: 64.0
                      z: -4.5
                      yaw: 90.0
                      pitch: 0.0
            """.trimIndent())
            val homes=FrontierHomes(plugin,FrontierConfig(enabled=true),mockk(),mockk(),mockk(),mockk())
            assertNull(homes.loadLocation("GUILD.saved.location"))
            val world=server.addSimpleWorld("late-frontier")
            val home=assertNotNull(homes.loadLocation("GUILD.saved.location"))
            assertEquals(world.uid,home.world.uid)
            assertEquals(12.5,home.x); assertEquals(-4.5,home.z)
        } finally { MockBukkit.unmock(); unmockkAll() }
    }

    private fun scenario(action: Fixture.() -> Unit) {
        val server = MockBukkit.mock()
        try { Fixture().action() } finally { MockBukkit.unmock(); unmockkAll() }
    }
    private class Fixture {
        val server = MockBukkit.getMock()!!
        val plugin = MockBukkit.createMockPlugin()
        val world = server.addSimpleWorld("frontier")
        val player = server.addPlayer()
        val guild = Guild(UUID.randomUUID(), "Keepers", createdAt=Instant.EPOCH)
        val claims = mockk<ClaimRepository>(relaxed=true)
        val partitions = mockk<PartitionRepository>(relaxed=true)
        val guilds = mockk<GuildService>(relaxed=true)
        val members = mockk<MemberService>(relaxed=true)
        var now = Instant.parse("2026-11-10T12:00:00Z")
        var claim = Claim(world.uid,player.uniqueId,null,Position3D(0,64,0),"Keep")
        var alive = true
        var member = true
        val destination = Location(world,1.5,64.0,1.5)
        val origin = Location(world,8.5,64.0,8.5)
        init {
            world.worldBorder.size=5000.0
            world.getBlockAt(1,63,1).type=Material.STONE
            world.getBlockAt(8,63,8).type=Material.STONE
            every { claims.getByPlayer(player.uniqueId) } answers { if(alive) setOf(claim) else emptySet() }
            every { claims.getByTeam(guild.id) } answers { if(alive) setOf(claim) else emptySet() }
            every { claims.getById(any()) } answers { if(alive && firstArg<UUID>()==claim.id) claim else null }
            every { partitions.getByPosition(any()) } answers {
                val p=firstArg<Position3D>()
                if(p.x in 0..15 && p.z in 0..15) setOf(Partition(claim.id,Area(Position2D(0,0),Position2D(15,15)))) else emptySet()
            }
            every { guilds.getPlayerGuilds(player.uniqueId) } answers { if(member) setOf(guild) else emptySet() }
            every { members.hasPermission(any(),any(),any()) } returns true
            player.teleport(destination)
        }
        fun homes(guildOnly: Boolean=false) = FrontierHomes(plugin,FrontierConfig(enabled=true,guildOnlyClaims=guildOnly),claims,partitions,guilds,members) { now }
        fun advance() { now=now.plusSeconds(6) }
    }
    @Test fun `home aliases share guild slot and cannot bypass setting permission`() = scenario {
        claim=claim.copy(teamId=guild.id)
        val h=homes(true)
        h.command(player,false,true)
        player.teleport(origin)
        every { members.hasPermission(any(),any(),any()) } returns false
        h.command(player,false,true)
        h.command(player,true,false); advance(); h.tick()
        assertEquals(destination,player.location)
    }
    @Test fun `guild home uses Manage Home independently of Manage Claims`() = scenario {
        claim=claim.copy(teamId=guild.id)
        every { members.hasPermission(any(),any(),any()) } returns false
        every { members.hasPermission(player.uniqueId,guild.id,RankPermission.MANAGE_HOME) } returns true
        val h=homes(true); h.command(player,true,true)
        assertNotNull(h.loadLocation("GUILD.${guild.id}.location"))
    }
    @Test fun `Manage Claims alone cannot set guild home`() = scenario {
        claim=claim.copy(teamId=guild.id)
        every { members.hasPermission(any(),any(),any()) } returns false
        every { members.hasPermission(player.uniqueId,guild.id,RankPermission.MANAGE_CLAIMS) } returns true
        val h=homes(true); h.command(player,true,true)
        assertNull(h.loadLocation("GUILD.${guild.id}.location"))
    }

    @Test fun `single personal slot survives reload and replacing it changes destination`() = scenario {
        homes().command(player,false,true)
        player.teleport(origin)
        homes().command(player,false,true)
        player.teleport(destination)
        val h=homes(); h.command(player,false,false); advance(); h.tick()
        assertEquals(origin,player.location)
    }
    @Test fun `claim loss and fresh foundation invalidate old home`() = scenario {
        val h=homes(); h.command(player,false,true); player.teleport(origin)
        h.command(player,false,false); alive=false; advance(); h.tick()
        assertEquals(origin,player.location)
        alive=true; claim=claim.copy(id=UUID.randomUUID())
        h.command(player,false,false); advance(); h.tick()
        assertEquals(origin,player.location)
    }
    @Test fun `moving during warmup cancels teleport`() = scenario {
        val h=homes(); h.command(player,false,true); player.teleport(origin); h.command(player,false,false)
        val moved=origin.clone().add(1.0,0.0,0.0); player.teleport(moved); advance(); h.tick()
        assertEquals(moved,player.location)
    }
    @Test fun `damage cancels warmup and prevents immediate new teleport`() = scenario {
        val h=homes(); h.command(player,false,true); player.teleport(origin); h.command(player,false,false)
        val event=mockk<EntityDamageEvent>(relaxed=true)
        every { event.entity } returns player
        h.damage(event); h.command(player,false,false); advance(); h.tick()
        assertEquals(origin,player.location)
    }
    @Test fun `home cannot be set in wilderness or another owners land`() = scenario {
        val h=homes(); player.teleport(Location(world,32.5,64.0,0.5)); world.getBlockAt(32,63,0).type=Material.STONE
        h.command(player,false,true); player.teleport(origin); h.command(player,false,false); advance(); h.tick()
        assertEquals(origin,player.location)
        claim=claim.copy(playerId=UUID.randomUUID()); player.teleport(destination); h.command(player,false,true)
        player.teleport(origin); h.command(player,false,false); advance(); h.tick(); assertEquals(origin,player.location)
    }
    @Test fun `raid beginning and obstructed home cancel pending teleport`() = scenario {
        val h=homes(); h.command(player,false,true); player.teleport(origin); h.command(player,false,false)
        now=Instant.parse("2026-11-10T20:00:00Z"); h.tick(); assertEquals(origin,player.location)
        now=Instant.parse("2026-11-11T12:00:00Z"); h.command(player,false,false)
        destination.block.type=Material.STONE; advance(); h.tick(); assertEquals(origin,player.location)
    }
    @Test fun `guild membership rechecked before arrival and rank required to set`() = scenario {
        claim=claim.copy(teamId=guild.id)
        every { members.hasPermission(any(),any(),any()) } returns false
        val h=homes(); h.command(player,true,true); player.teleport(origin); h.command(player,true,false); advance(); h.tick()
        assertEquals(origin,player.location)
        every { members.hasPermission(any(),any(),any()) } returns true
        player.teleport(destination); h.command(player,true,true); player.teleport(origin); h.command(player,true,false)
        member=false; advance(); h.tick(); assertEquals(origin,player.location)
    }

    @Test fun `guild home survives reload with facing and remains a single slot`() = scenario {
        claim=claim.copy(teamId=guild.id)
        destination.yaw=90f; destination.pitch=15f
        player.teleport(destination); homes().command(player,true,true)
        player.teleport(origin)
        val h=homes(); h.command(player,true,false); advance(); h.tick()
        assertEquals(destination,player.location)
        assertNull(h.loadLocation("PERSONAL.${player.uniqueId}.location"))
    }

    @Test fun `teleport cooldown blocks before thirty seconds and allows at the boundary`() = scenario {
        val h=homes(); h.command(player,false,true); player.teleport(origin)
        h.command(player,false,false); advance(); h.tick()
        assertEquals(destination,player.location)
        val completed=now
        player.teleport(origin); now=completed.plusSeconds(29)
        h.command(player,false,false); now=completed.plusSeconds(30); h.tick()
        assertEquals(origin,player.location)
        h.command(player,false,false); advance(); h.tick()
        assertEquals(destination,player.location)
    }

    @Test fun `failed home save preserves previous home in memory and after reload`() = scenario {
        val h=homes(); h.command(player,false,true)
        // A directory at the temporary-file path reliably rejects the write on all platforms.
        assertTrue(plugin.dataFolder.resolve("frontier-homes.yml.tmp").mkdir())
        player.teleport(origin); h.command(player,false,true)
        val path="PERSONAL.${player.uniqueId}.location"
        assertEquals(destination,h.loadLocation(path))
        assertEquals(destination,homes().loadLocation(path))
    }
}
