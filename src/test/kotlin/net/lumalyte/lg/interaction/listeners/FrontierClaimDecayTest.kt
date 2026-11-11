package net.lumalyte.lg.interaction.listeners

import io.mockk.*
import net.lumalyte.lg.application.persistence.ClaimRepository
import net.lumalyte.lg.application.services.*
import net.lumalyte.lg.domain.entities.Claim
import net.lumalyte.lg.domain.entities.Member
import net.lumalyte.lg.domain.values.Position3D
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class FrontierClaimDecayTest {
    private fun scenario(body: Fixture.() -> Unit) {
        MockBukkit.mock()
        try { Fixture().body() } finally { MockBukkit.unmock(); unmockkAll() }
    }
    private class Fixture {
        val server = MockBukkit.getMock()!!
        val plugin = MockBukkit.createMockPlugin()
        val world = server.addSimpleWorld("frontier")
        val owner = UUID.randomUUID()
        val claim = Claim(world.uid, owner, null, Position3D(0,64,0), "Keep")
        val claims = mockk<ClaimRepository>()
        val members = mockk<MemberService>(relaxed = true)
        val release = mockk<FrontierClaimRelease>(relaxed = true)
        val settings = YamlConfiguration()
        val day = 86400000L
        var now = 100 * day
        init { settings.set("decay.enabled",true); plugin.dataFolder.mkdirs(); every { claims.getAll() } returns setOf(claim) }
        fun decay() = FrontierClaimDecay(plugin,settings,claims,members,release) { now }
    }
    @Test fun `missing decay setting never expires claims`() = scenario {
        settings.set("decay.enabled",null)
        decay().checkTerritories(); now += 100 * day; decay().checkTerritories()
        verify(exactly = 0) { release.release(any<FrontierOwner>()) }
        verify(exactly = 0) { claims.getAll() }
    }
    @Test fun `fresh grace survives reload and expires only at deadline`() = scenario {
        decay().checkTerritories()
        verify(exactly = 0) { release.release(any<FrontierOwner>()) }
        now += 14 * day - 1
        decay().checkTerritories()
        verify(exactly = 0) { release.release(any<FrontierOwner>()) }
        now++
        decay().checkTerritories()
        verify(exactly = 1) { release.release(FrontierOwner(FrontierOwnerType.PERSONAL, owner)) }
        assertTrue(plugin.dataFolder.resolve("frontier-activity.yml").isFile)
    }
    @Test fun `online owner protects expired territory`() = scenario {
        val player = server.addPlayer()
        claim.playerId = player.uniqueId
        val decay = decay(); decay.checkTerritories(); now += 30 * day
        decay.checkTerritories()
        verify(exactly = 0) { release.release(any<FrontierOwner>()) }
    }
    @Test fun `departed member activity does not preserve guild territory`() = scenario {
        val guild = UUID.randomUUID(); val current = UUID.randomUUID(); val departed = UUID.randomUUID()
        claim.teamId = guild
        every { members.getGuildMembers(guild) } returns setOf(Member(current,guild,UUID.randomUUID(),Instant.EPOCH))
        decay().checkTerritories(); now += 30 * day
        val file = plugin.dataFolder.resolve("frontier-activity.yml")
        val data = YamlConfiguration().apply { load(file); set("players.$departed", now); save(file) }
        decay().checkTerritories()
        verify(exactly = 1) { release.release(FrontierOwner(FrontierOwnerType.GUILD, guild)) }
    }
    @Test fun `corrupt state disables expiry without overwriting evidence`() = scenario {
        val file = plugin.dataFolder.resolve("frontier-activity.yml")
        file.writeText("broken: [unterminated")
        decay().checkTerritories(); now += 30 * day; decay().checkTerritories()
        verify(exactly = 0) { release.release(any<FrontierOwner>()) }
        assertEquals("broken: [unterminated", file.readText())
    }
    @Test fun `failed atomic save prevents release`() = scenario {
        decay().checkTerritories(); now += 30 * day
        plugin.dataFolder.resolve("frontier-activity.yml.tmp").mkdirs()
        decay().checkTerritories()
        verify(exactly = 0) { release.release(any<FrontierOwner>()) }
    }
}
