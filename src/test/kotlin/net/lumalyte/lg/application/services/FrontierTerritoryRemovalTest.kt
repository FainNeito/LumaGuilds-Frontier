package net.lumalyte.lg.application.services

import io.mockk.*
import net.lumalyte.lg.application.persistence.*
import net.lumalyte.lg.config.FrontierConfig
import net.lumalyte.lg.domain.entities.*
import net.lumalyte.lg.domain.values.*
import net.lumalyte.lg.interaction.listeners.FrontierAlertListener
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class FrontierTerritoryRemovalTest {
    @Test fun `leaving territory sends chat even when destination is wilderness`() {
        val world=mockk<org.bukkit.World>(); every { world.uid } returns UUID.randomUUID(); every { world.name } returns "frontier"
        val player=mockk<org.bukkit.entity.Player>(relaxed=true)
        val claim=Claim(world.uid,UUID.randomUUID(),UUID.randomUUID(),Position3D(0,64,0),"Ashen Keep")
        val lookup=mockk<net.lumalyte.lg.application.actions.claim.GetClaimAtPosition>()
        every { lookup.execute(world.uid,Position2D(15,0)) } returns net.lumalyte.lg.application.results.claim.GetClaimAtPositionResult.Success(claim)
        every { lookup.execute(world.uid,Position2D(16,0)) } returns net.lumalyte.lg.application.results.claim.GetClaimAtPositionResult.NoClaimFound
        val listener=FrontierAlertListener(mockk(),FrontierConfig(enabled=true),lookup,mockk())
        listener.onMove(org.bukkit.event.player.PlayerMoveEvent(player,org.bukkit.Location(world,15.0,64.0,0.0),org.bukkit.Location(world,16.0,64.0,0.0)))
        verify(exactly=1) { player.sendMessage(match<net.kyori.adventure.text.Component> { net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it).contains("Leaving Ashen Keep.") }) }
    }
    @Test fun `cannot unclaim founding or bridge but can unclaim connected outer edge`() {
        val world=UUID.randomUUID(); val player=UUID.randomUUID()
        val root=Claim(world,player,null,Position3D(0,64,0),"Keep")
        val claims=mockk<ClaimRepository> { every { getByPlayer(player) } returns setOf(root) }
        val land=(0..2).map { x -> Partition(root.id,Area(Position2D(x*16,0),Position2D(x*16+15,15))) }.toMutableList()
        val partitions=mockk<PartitionRepository> {
            every { getByClaim(root.id) } answers { land.toSet() }
            every { remove(any()) } answers { land.removeIf { it.id==firstArg<UUID>() } }
        }
        val territory=FrontierTerritory(claims,partitions)
        val owner=FrontierOwner(FrontierOwnerType.PERSONAL,player)
        assertFalse(territory.unclaim(owner,world,Position2D(0,0)))
        assertFalse(territory.unclaim(owner,world,Position2D(1,0)))
        assertFalse(territory.unclaim(owner,UUID.randomUUID(),Position2D(2,0)))
        assertTrue(territory.unclaim(owner,world,Position2D(2,0)))
        assertEquals(setOf(Position2D(0,0),Position2D(1,0)),territory.chunks(owner))
        verify(exactly=1) { partitions.remove(any()) }
    }
    @Test fun `TAB countdown switches from next raid to closing countdown at exact boundary`() {
        val listener=FrontierAlertListener(mockk(),FrontierConfig(enabled=true),mockk(),mockk())
        assertEquals("Claims raidable in 1m 00s",listener.countdown(Instant.parse("2026-11-10T19:59:00Z")))
        assertEquals("CLAIMS RAIDABLE - closes in 1h 00m 00s",listener.countdown(Instant.parse("2026-11-10T20:00:00Z")))
        assertEquals("Claims raidable in 23h 00m 00s",listener.countdown(Instant.parse("2026-11-10T21:00:00Z")))
    }
}
