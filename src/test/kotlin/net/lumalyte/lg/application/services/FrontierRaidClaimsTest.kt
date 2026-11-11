package net.lumalyte.lg.application.services

import io.mockk.every
import io.mockk.mockk
import net.lumalyte.lg.application.persistence.ClaimRepository
import net.lumalyte.lg.application.persistence.PartitionRepository
import net.lumalyte.lg.config.FrontierConfig
import net.lumalyte.lg.domain.entities.Claim
import net.lumalyte.lg.domain.entities.Partition
import net.lumalyte.lg.domain.entities.WarBannerState
import net.lumalyte.lg.domain.values.Area
import net.lumalyte.lg.domain.values.FrontierChunk
import net.lumalyte.lg.domain.values.Position2D
import net.lumalyte.lg.domain.values.Position3D
import net.lumalyte.lg.infrastructure.persistence.claims.FrontierRaidClaimStoreFiles
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class FrontierRaidClaimsTest {
    private val now = Instant.parse("2026-11-10T20:10:00Z").toEpochMilli()
    private val world = UUID.randomUUID()
    private val attacker = UUID.randomUUID()
    private val defender = UUID.randomUUID()
    private val chunk = FrontierChunk(world, 1, 0)
    private val claimRepo = mockk<ClaimRepository>()
    private val partitionRepo = mockk<PartitionRepository>()
    private val stored = mutableMapOf<UUID, FrontierRaidClaim>()
    private var state: WarBannerState? = null
    private val store = object : FrontierRaidClaimStore {
        override fun all() = stored.values.toList()
        override fun save(claim: FrontierRaidClaim) { stored[claim.attacker] = claim }
        override fun delete(attacker: UUID) { stored.remove(attacker) }
    }
    private val owners = mutableMapOf<UUID, Claim>()
    private val chunks = mutableMapOf<Position2D, Set<Partition>>()

    private fun territory(at: Position2D, guild: UUID? = defender) {
        val claim = Claim(world, UUID.randomUUID(), guild, Position3D(at.x * 16, 70, at.z * 16), "Test")
        owners[claim.id] = claim
        chunks[at] = setOf(Partition(claim.id, Area(Position2D(at.x * 16, at.z * 16), Position2D(at.x * 16 + 15, at.z * 16 + 15))))
    }

    private fun service(): FrontierRaidClaims {
        every { claimRepo.getById(any()) } answers { owners[firstArg()] }
        every { claimRepo.getByTeam(attacker) } returns setOf(Claim(world, UUID.randomUUID(), attacker, Position3D(100, 70, 100), "Base"))
        every { partitionRepo.getByChunk(any()) } answers { chunks[firstArg()] ?: emptySet() }
        return FrontierRaidClaims(FrontierConfig(enabled = true), claimRepo, partitionRepo, store) { state }
    }

    @Test fun `only side adjacent wilderness beside a foreign guild is eligible`() {
        territory(Position2D(0, 0))
        val raids = service()
        assertEquals(FrontierRaidClaimCheck.Allowed(defender), raids.check(attacker, chunk, null, now))
        assertIs<FrontierRaidClaimCheck.Rejected>(raids.check(attacker, FrontierChunk(world, 1, 1), null, now))
        assertIs<FrontierRaidClaimCheck.Rejected>(raids.check(attacker, FrontierChunk(world, 0, 0), null, now))
        assertIs<FrontierRaidClaimCheck.Rejected>(raids.check(attacker, chunk, UUID.randomUUID(), now))
        assertIs<FrontierRaidClaimCheck.Rejected>(raids.check(attacker, chunk, null, now + 3_600_000))
    }

    @Test fun `personal land does not count as a target`() {
        territory(Position2D(0, 0), null)
        val raids = service()
        assertIs<FrontierRaidClaimCheck.Rejected>(raids.check(attacker, chunk, null, now))
    }

    @Test fun `banner cooldown prevents staging an unusable forward position`() {
        territory(Position2D(0, 0))
        val raids = service()
        state = WarBannerState(attacker, UUID.randomUUID(), world, 17, 70, 2,
            UUID.randomUUID(), UUID.randomUUID(), now - 90_000, now - 20_000,
            now + 86_400_000, false)
        assertIs<FrontierRaidClaimCheck.Rejected>(raids.check(attacker, chunk, null, now))
    }

    @Test fun `multiple neighboring guilds need explicit target`() {
        territory(Position2D(0, 0))
        val other = UUID.randomUUID()
        territory(Position2D(2, 0), other)
        val raids = service()
        assertIs<FrontierRaidClaimCheck.Rejected>(raids.check(attacker, chunk, null, now))
        assertEquals(FrontierRaidClaimCheck.Allowed(other), raids.check(attacker, chunk, other, now))
    }

    @Test fun `one staging claim per guild with a two minute banner deadline`() {
        territory(Position2D(0, 0))
        val raids = service()
        assertEquals(FrontierRaidClaimCheck.Allowed(defender), raids.create(attacker, chunk, null, now))
        assertNotNull(raids.at(chunk, now))
        assertTrue(raids.isBeingRaided(defender, now))
        assertFalse(raids.isBeingRaided(attacker, now))
        assertIs<FrontierRaidClaimCheck.Rejected>(raids.create(attacker, chunk, null, now + 1_000))
        raids.reconcile(now + 120_000) { true }
        assertNull(raids.at(chunk, now + 120_000))
        assertFalse(raids.isBeingRaided(defender, now + 120_000))
        assertTrue(stored.isEmpty())
    }

    @Test fun `banner binds only to original raid chunk and destruction clears territory`() {
        territory(Position2D(0, 0))
        val raids = service()
        raids.create(attacker, chunk, null, now)
        fun bannerAt(x: Int) = WarBannerState(attacker, UUID.randomUUID(), world, x, 70, 2,
            UUID.randomUUID(), UUID.randomUUID(), now + 1_000, now + 901_000,
            now + 86_400_000, true)
        val wrong = bannerAt(33)
        assertFalse(raids.bind(wrong, now + 1_000))
        state = bannerAt(17)
        assertTrue(raids.bind(requireNotNull(state), now + 1_000))
        assertNotNull(raids.at(chunk, now + 121_000))
        assertFalse(raids.abandon(attacker, now + 121_000))
        raids.removeBanner(requireNotNull(state))
        assertNull(raids.at(chunk, now + 121_000))
        assertTrue(stored.isEmpty())
    }

    @Test fun `crashed or expired banner does not retain forward territory`() {
        territory(Position2D(0, 0))
        val raids = service()
        raids.create(attacker, chunk, null, now)
        state = WarBannerState(attacker, UUID.randomUUID(), world, 17, 70, 2,
            UUID.randomUUID(), UUID.randomUUID(), now + 1_000, now + 901_000,
            now + 86_400_000, true)
        assertTrue(raids.bind(requireNotNull(state), now + 1_000))
        state = null // Core no longer considers the banner active after restart/destruction.
        raids.reconcile(now + 2_000) { record ->
            assertNotNull(record.bannerPosition)
            true // Simulates successful removal of the persisted physical banner.
        }
        assertTrue(stored.isEmpty())
        assertNull(raids.forGuild(attacker, now + 2_000))
    }

    @Test fun `raid hour end unclaims immediately but retains banner cleanup until it succeeds`() {
        territory(Position2D(0, 0))
        val raids = service()
        val nearEnd = Instant.parse("2026-11-10T20:59:00Z").toEpochMilli()
        val end = Instant.parse("2026-11-10T21:00:00Z").toEpochMilli()
        raids.create(attacker, chunk, null, nearEnd)
        state = WarBannerState(attacker, UUID.randomUUID(), world, 17, 70, 2,
            UUID.randomUUID(), UUID.randomUUID(), nearEnd + 1_000, end,
            nearEnd + 86_400_000, true)
        assertTrue(raids.bind(requireNotNull(state), nearEnd + 1_000))
        var calls = 0
        raids.reconcile(end) { calls++; false } // World not loaded or block removal failed.
        assertEquals(1, calls)
        assertNull(raids.at(chunk, end))
        assertFalse(raids.isBeingRaided(defender, end))
        assertNotNull(stored[attacker], "Keep the cleanup journal until the banner is gone")
        assertIs<FrontierRaidClaimCheck.Rejected>(raids.check(attacker, chunk, null,
            Instant.parse("2026-11-11T20:10:00Z").toEpochMilli()))
        raids.reconcile(end + 1_000) { true }
        assertTrue(stored.isEmpty())
    }

    @Test fun `file records survive restart and can be removed atomically`(@TempDir directory: Path) {
        val files = FrontierRaidClaimStoreFiles(directory)
        val record = FrontierRaidClaim(attacker, defender, chunk, now, now + 3_000_000, now + 120_000)
        files.save(record)
        assertEquals(listOf(record), FrontierRaidClaimStoreFiles(directory).all())
        val bound = record.copy(bannerId = UUID.randomUUID(), bannerEnd = now + 901_000,
            bannerPosition = Position3D(17, 70, 2))
        files.save(bound)
        assertEquals(listOf(bound), FrontierRaidClaimStoreFiles(directory).all())
        files.delete(attacker)
        assertTrue(files.all().isEmpty())
    }
}
