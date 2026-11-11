package net.lumalyte.lg.application.services

import io.mockk.*
import net.lumalyte.lg.application.persistence.ClaimRepository
import net.lumalyte.lg.domain.entities.Claim
import net.lumalyte.lg.domain.entities.Guild
import net.lumalyte.lg.domain.values.Position3D
import net.lumalyte.lg.infrastructure.services.FrontierObjectives
import net.lumalyte.lg.interaction.listeners.FrontierObjectiveListener
import org.bukkit.*
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class FrontierObjectiveEventsTest {
    private val world = mockk<World> { every { uid } returns UUID.randomUUID() }
    private val location = Location(world, 1.0, 64.0, 1.0)
    private val block = mockk<Block> {
        every { location } returns this@FrontierObjectiveEventsTest.location
        every { world } returns this@FrontierObjectiveEventsTest.world
    }
    private val player = mockk<Player>(relaxed = true) { every { uniqueId } returns UUID.randomUUID() }
    private val objectives = mockk<FrontierObjectives>()
    private val siege = mockk<FrontierSiege>()
    private val claims = mockk<ClaimRepository>()
    private val vaults = mockk<GuildVaultService>()
    private val members = mockk<MemberService>()
    private val listener = FrontierObjectiveListener(objectives, claims, vaults, members, mockk(relaxed = true))

    @Test fun `outsider vault break is cancelled and routed to siege`() {
        val guild = Guild(UUID.randomUUID(), "Raiders", createdAt = Instant.now())
        every { block.type } returns Material.CHEST
        every { vaults.getGuildForVaultChest(location) } returns guild
        every { members.getMember(player.uniqueId, guild.id) } returns null
        every { objectives.siege } returns siege
        every { siege.hit(any(), any(), false, any()) } returns FrontierSiegeResult.Progress(1, 20)
        val event = BlockBreakEvent(block, player)
        listener.onBreak(event)
        assertTrue(event.isCancelled)
        val expectedKey = FrontierObjectives.key(location)
        verify { siege.hit(FrontierOwner(FrontierOwnerType.GUILD, guild.id), expectedKey, false, any()) }
        verify(exactly = 0) { vaults.removeVaultChest(any(), any()) }
    }

    @Test fun `solo owner is marked defender and secondary bells cannot take raid damage`() {
        val claim = Claim(world.uid, player.uniqueId, null, Position3D(1, 64, 1), "Solo")
        every { block.type } returns Material.BELL
        every { claims.getByPosition(any(), world.uid) } returns claim
        every { objectives.primaryAnchor(claim) } returns true
        every { objectives.soloAt(location) } returns claim
        every { objectives.siege } returns siege
        every { siege.hit(any(), any(), true, any()) } returns FrontierSiegeResult.Defender
        val event = BlockBreakEvent(block, player)
        listener.onBreak(event)
        assertTrue(event.isCancelled)
        val expectedOwner = FrontierOwner(FrontierOwnerType.PERSONAL, player.uniqueId)
        verify(exactly = 1) { siege.hit(expectedOwner, any(), true, any()) }
        every { objectives.primaryAnchor(claim) } returns false
        every { objectives.soloAt(location) } returns null
        listener.onBreak(BlockBreakEvent(block, player))
        verify(exactly = 1) { siege.hit(any(), any(), any(), any()) }
    }

    @Test fun `block explosions exclude vaults without invoking removal`() {
        every { block.type } returns Material.CHEST
        every { vaults.getGuildForVaultChest(location) } returns Guild(UUID.randomUUID(), "Safe", createdAt = Instant.now())
        val blocks = mutableListOf(block)
        val event = mockk<BlockExplodeEvent> { every { blockList() } returns blocks }
        listener.onBlockExplosion(event)
        assertEquals(emptyList(), blocks)
        verify(exactly = 0) { vaults.removeVaultChest(any(), any()) }
    }
}
