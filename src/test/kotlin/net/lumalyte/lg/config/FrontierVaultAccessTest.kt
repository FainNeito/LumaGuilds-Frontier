package net.lumalyte.lg.config

import io.mockk.*
import net.lumalyte.lg.application.persistence.*
import net.lumalyte.lg.application.services.FrontierVaultAccess
import net.lumalyte.lg.domain.entities.*
import net.lumalyte.lg.domain.values.Position3D
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class FrontierVaultAccessTest {
    private val claims = mockk<ClaimRepository>()
    private val partitions = mockk<PartitionRepository>()
    private val guild = UUID.randomUUID()
    private val world = UUID.randomUUID()
    private val position = Position3D(160,64,160)
    private val access = FrontierVaultAccess(claims, partitions)
    private fun territory(owner: UUID?, claimWorld: UUID = world) {
        val claim = mockk<Claim>(); val partition = mockk<Partition>(); val id = UUID.randomUUID()
        every { partition.claimId } returns id
        every { partitions.getByPosition(position) } returns setOf(partition)
        every { claims.getById(id) } returns claim
        every { claim.teamId } returns owner
        every { claim.worldId } returns claimWorld
    }
    @Test fun `own guild territory permits access`() { territory(guild); assertTrue(access.allowed(guild,world,position,false)) }
    @Test fun `combat denies even in own claim`() { territory(guild); assertFalse(access.allowed(guild,world,position,true)); verify(exactly=0) { partitions.getByPosition(any()) } }
    @Test fun `foreign guild territory denies access`() { territory(UUID.randomUUID()); assertFalse(access.allowed(guild,world,position,false)) }
    @Test fun `legacy solo territory does not count as guild land`() { territory(null); assertFalse(access.allowed(guild,world,position,false)) }
    @Test fun `same coordinates in another world deny access`() { territory(guild,UUID.randomUUID()); assertFalse(access.allowed(guild,world,position,false)) }
    @Test fun `wilderness denies access`() { every { partitions.getByPosition(position) } returns emptySet(); assertFalse(access.allowed(guild,world,position,false)) }
}
