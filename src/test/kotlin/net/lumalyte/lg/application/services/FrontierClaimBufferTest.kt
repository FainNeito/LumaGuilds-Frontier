package net.lumalyte.lg.application.services

import io.mockk.*
import net.lumalyte.lg.application.actions.claim.IsNewClaimLocationValid
import net.lumalyte.lg.application.persistence.*
import net.lumalyte.lg.application.results.claim.IsNewClaimLocationValidResult
import net.lumalyte.lg.config.*
import net.lumalyte.lg.domain.entities.*
import net.lumalyte.lg.domain.values.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class FrontierClaimBufferTest {
    private val world = UUID.randomUUID()
    private val owner = UUID.randomUUID()
    private val claims = mockk<ClaimRepository>()
    private val partitions = mockk<PartitionRepository>()
    private val worlds = mockk<WorldManipulationService>()
    private val config = MainConfig(claimGeometry = ClaimGeometry.CHUNKS, frontier = FrontierConfig(enabled = true))
    private fun validator(existingOwner: UUID = UUID.randomUUID(), guild: UUID? = null): IsNewClaimLocationValid {
        val claim = Claim(world, existingOwner, guild, Position3D(0, 64, 0), "Existing")
        every { claims.getById(claim.id) } returns claim
        every { partitions.getByChunk(any()) } returns setOf(Partition(claim.id, Area(Position2D(0, 0), Position2D(15, 15))))
        every { worlds.isInsideWorldBorder(any(), any()) } returns true
        every { worlds.isClaimWorld(any(), any()) } returns true
        return IsNewClaimLocationValid(claims, partitions, worlds, config)
    }
    @Test fun `foreign side and diagonal adjacency require four empty chunks`() {
        val valid = validator()
        for ((x,z) in listOf(16 to 0, 32 to 0, 48 to 0, 64 to 0,
                -1 to 0, -64 to 0, 64 to 64, -64 to -64))
            assertEquals(IsNewClaimLocationValidResult.Overlap, valid.execute(Position3D(x,64,z),world,owner))
        for ((x,z) in listOf(80 to 0, -80 to 0, 80 to 80, -80 to -80))
            assertEquals(IsNewClaimLocationValidResult.Valid, valid.execute(Position3D(x,64,z),world,owner))
    }
    @Test fun `same owner may extend contiguously but cannot overlap`() {
        val valid = validator(owner)
        assertEquals(IsNewClaimLocationValidResult.Valid, valid.execute(Position3D(16,64,0),world,owner))
        assertEquals(IsNewClaimLocationValidResult.Overlap, valid.execute(Position3D(1,64,1),world,owner))
    }
    @Test fun `guild members can create staging claims beside their guild`() {
        val guild = UUID.randomUUID()
        val valid = validator(guild = guild)
        assertEquals(IsNewClaimLocationValidResult.Valid, valid.execute(Position3D(16,64,0),world,owner,setOf(guild)))
        assertEquals(IsNewClaimLocationValidResult.Overlap, valid.execute(Position3D(16,64,0),world,owner))
    }
    @Test fun `non claim dimension is rejected before querying land`() {
        val valid = validator()
        every { worlds.isClaimWorld(world, any()) } returns false
        assertEquals(IsNewClaimLocationValidResult.TooCloseToWorldBorder, valid.execute(Position3D(100,64,0),world,owner))
        verify(exactly = 0) { partitions.getByChunk(any()) }
    }
    @Test fun `transfer cannot manufacture adjacent foreign ownership`() {
        val existing = Claim(world, owner, null, Position3D(0,64,0), "Transfer")
        val neighbor = Claim(world, owner, null, Position3D(16,64,0), "Remain")
        val ownPartition = Partition(existing.id, Area(Position2D(0,0),Position2D(15,15)))
        val neighborPartition = Partition(neighbor.id, Area(Position2D(16,0),Position2D(31,15)))
        every { claims.getById(existing.id) } returns existing
        every { claims.getById(neighbor.id) } returns neighbor
        every { claims.getByName(any(), any()) } returns null
        every { claims.getByPlayer(any()) } returns emptySet()
        every { partitions.getByClaim(existing.id) } returns setOf(ownPartition)
        every { partitions.getByChunk(any()) } returns setOf(ownPartition, neighborPartition)
        val transfers = mockk<ClaimTransferRequestRepository>()
        every { transfers.hasActive(any(), any(), any()) } returns true
        val action = net.lumalyte.lg.application.actions.claim.transfer.AcceptTransferRequest(claims, mockk(), partitions, transfers, config)
        assertEquals(net.lumalyte.lg.application.results.claim.transfer.AcceptTransferRequestResult.RaidLocked,
            action.execute(existing.id, UUID.randomUUID(), "Transferred"))
        verify(exactly = 0) { transfers.consumeClaim(any(), any(), any()) }
    }}
