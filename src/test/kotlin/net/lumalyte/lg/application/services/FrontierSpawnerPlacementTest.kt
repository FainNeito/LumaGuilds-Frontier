package net.lumalyte.lg.application.services

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.lumalyte.lg.application.persistence.ClaimRepository
import net.lumalyte.lg.application.persistence.PartitionRepository
import net.lumalyte.lg.domain.entities.Claim
import net.lumalyte.lg.domain.entities.Partition
import net.lumalyte.lg.domain.values.Area
import net.lumalyte.lg.domain.values.FrontierChunk
import net.lumalyte.lg.domain.values.Position2D
import net.lumalyte.lg.domain.values.Position3D
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrontierSpawnerPlacementTest {
    private val world = UUID.randomUUID()
    private val guild = UUID.randomUUID()
    private val foreign = UUID.randomUUID()
    private val chunk = FrontierChunk(world, -1, 2)
    private val claims = mockk<ClaimRepository>()
    private val partitions = mockk<PartitionRepository>()
    private val claimById = mutableMapOf<UUID, Claim>()
    private val chunks = mutableMapOf<Position2D, Set<Partition>>()
    private var raidChunk: FrontierChunk? = null
    private val policy = FrontierSpawnerPlacement(claims, partitions) { current, _ -> current == raidChunk }

    private fun territory(at: FrontierChunk = chunk, owner: UUID? = guild) {
        val claim = Claim(at.worldId, UUID.randomUUID(), owner,
            Position3D(at.x * 16, 70, at.z * 16), "Base")
        claimById[claim.id] = claim
        val area = Area(Position2D(at.x * 16, at.z * 16), Position2D(at.x * 16 + 15, at.z * 16 + 15))
        val partition = Partition(claim.id, area)
        chunks[Position2D(at.x, at.z)] = (chunks[Position2D(at.x, at.z)] ?: emptySet()) + partition
    }

    private fun setup() {
        every { claims.getById(any()) } answers { claimById[firstArg()] }
        every { partitions.getByChunk(any()) } answers { chunks[firstArg()] ?: emptySet() }
    }

    @Test fun `guild may place a spawner on its own permanent chunk including negative X`() {
        territory()
        setup()
        assertTrue(policy.allowed(chunk, setOf(guild), 1L))
    }

    @Test fun `wilderness and players without a guild are rejected`() {
        setup()
        assertFalse(policy.allowed(chunk, setOf(guild), 1L))
        territory()
        assertFalse(policy.allowed(chunk, emptySet(), 1L))
    }

    @Test fun `temporary raid claims never count even when a permanent record overlaps`() {
        territory()
        setup()
        raidChunk = chunk
        assertFalse(policy.allowed(chunk, setOf(guild), 1L))
        verify(exactly = 0) { partitions.getByChunk(any()) }
        raidChunk = null
        assertTrue(policy.allowed(chunk, setOf(guild), 2L))
    }

    @Test fun `foreign allied and personal owners cannot authorize spawner placement`() {
        territory(owner = foreign)
        setup()
        assertFalse(policy.allowed(chunk, setOf(guild), 1L))
        // Diplomatic alliances do not confer infrastructure permissions.
        territory(owner = null)
        assertFalse(policy.allowed(chunk, setOf(guild), 2L))
        territory(owner = guild)
        // Fail closed if conflicting permanent records overlap the same chunk.
        assertFalse(policy.allowed(chunk, setOf(guild), 3L))
    }

    @Test fun `same chunk coordinates in another world cannot authorize placement`() {
        territory(at = chunk.copy(worldId = UUID.randomUUID()))
        setup()
        assertFalse(policy.allowed(chunk, setOf(guild), 1L))
        territory()
        // Unrelated world occupancy at matching coordinates must not hide valid Frontier land.
        assertTrue(policy.allowed(chunk, setOf(guild), 2L))
    }

    @Test fun `missing claim record fails closed`() {
        territory()
        claimById.clear()
        setup()
        assertFalse(policy.allowed(chunk, setOf(guild), 1L))
    }
}
