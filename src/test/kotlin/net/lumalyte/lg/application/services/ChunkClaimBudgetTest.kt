package net.lumalyte.lg.application.services

import io.mockk.every
import io.mockk.mockk
import net.lumalyte.lg.application.persistence.ClaimRepository
import net.lumalyte.lg.application.persistence.PartitionRepository
import net.lumalyte.lg.domain.entities.Claim
import net.lumalyte.lg.domain.entities.Partition
import net.lumalyte.lg.domain.values.Area
import net.lumalyte.lg.domain.values.Position2D
import net.lumalyte.lg.domain.values.Position3D
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChunkClaimBudgetTest {
    @Test fun `guild land does not consume the original placers personal allowance`() {
        val player = UUID.randomUUID()
        val guild = UUID.randomUUID()
        val world = UUID.randomUUID()
        val personal = Claim(world, player, null, Position3D(0, 64, 0), "Home")
        val shared = Claim(world, player, guild, Position3D(32, 64, 0), "Guild")
        val claims = mockk<ClaimRepository>()
        val partitions = mockk<PartitionRepository>()
        every { claims.getByPlayer(player) } returns setOf(personal, shared)
        every { claims.getByTeam(guild) } returns setOf(shared)
        every { partitions.getByClaim(personal.id) } returns setOf(
            Partition(personal.id, Area(Position2D(0, 0), Position2D(15, 15))))
        every { partitions.getByClaim(shared.id) } returns setOf(
            Partition(shared.id, Area(Position2D(32, 0), Position2D(63, 15))))
        val budget = ChunkClaimBudget(claims, partitions)
        assertEquals(1L, budget.used(player))
        assertEquals(2L, budget.used(player, guild))
        assertTrue(budget.canAdd(player, null, 1, 2))
        assertFalse(budget.canAdd(player, guild, 1, 2))
    }
}
