package net.lumalyte.lg.application.services

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.lumalyte.lg.application.persistence.*
import net.lumalyte.lg.domain.entities.Claim
import net.lumalyte.lg.domain.values.Position3D
import net.lumalyte.lg.infrastructure.persistence.claims.FrontierReleaseJournalFiles
import net.lumalyte.lg.infrastructure.persistence.claims.FrontierSiegeStoreFiles
import net.lumalyte.lg.domain.values.FrontierRaidSchedule
import java.time.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FrontierClaimReleaseTest {
    @TempDir lateinit var directory: Path
    private val claims = mockk<ClaimRepository>()
    private val partitions = mockk<PartitionRepository>(relaxed = true)
    private val permissions = mockk<ClaimPermissionRepository>(relaxed = true)
    private val flags = mockk<ClaimFlagRepository>(relaxed = true)
    private val access = mockk<PlayerAccessRepository>(relaxed = true)
    private fun service(journal: FrontierReleaseJournal) = FrontierClaimRelease(
        claims, partitions, permissions, flags, access, journal)
    private fun claim(owner: UUID, guild: UUID? = null) =
        Claim(UUID.randomUUID(), owner, guild, Position3D(0, 64, 0), "Base")

    @Test fun `personal destruction excludes guild land and recovers interrupted cleanup`() {
        val owner = UUID.randomUUID()
        val personal = claim(owner)
        val guildLand = claim(owner, UUID.randomUUID())
        val remaining = mutableMapOf(personal.id to personal, guildLand.id to guildLand)
        every { claims.getByPlayer(owner) } returns remaining.values.toSet()
        every { claims.getById(any()) } answers { remaining[firstArg()] }
        every { claims.remove(any()) } answers { remaining.remove(firstArg()) != null }
        every { partitions.removeByClaim(personal.id) } throws IllegalStateException("storage unavailable")
        val journal = FrontierReleaseJournalFiles(directory)
        assertFailsWith<IllegalStateException> {
            service(journal).release(FrontierOwner(FrontierOwnerType.PERSONAL, owner))
        }
        assertEquals(setOf(personal.id), journal.pending().single().claimIds)
        every { partitions.removeByClaim(personal.id) } returns true
        // New journal/service instances represent recovery after a process restart.
        service(FrontierReleaseJournalFiles(directory)).recover()
        assertTrue(journal.pending().isEmpty())
        assertEquals(setOf(guildLand.id), remaining.keys)
        verify(exactly = 0) { claims.remove(guildLand.id) }
    }

    @Test fun `recovery does not delete land transferred to a different owner`() {
        val owner = UUID.randomUUID()
        val transferred = claim(UUID.randomUUID())
        val journal = FrontierReleaseJournalFiles(directory)
        journal.save(FrontierReleasePlan(UUID.randomUUID(),
            FrontierOwner(FrontierOwnerType.PERSONAL, owner), setOf(transferred.id)))
        every { claims.getById(transferred.id) } returns transferred
        service(journal).recover()
        verify(exactly = 0) { claims.remove(any()) }
        verify(exactly = 0) { partitions.removeByClaim(any()) }
        assertTrue(journal.pending().isEmpty())
    }

    @Test fun `failed journal write prevents any claim deletion`() {
        val owner = UUID.randomUUID()
        every { claims.getByPlayer(owner) } returns setOf(claim(owner))
        val journal = mockk<FrontierReleaseJournal>()
        every { journal.save(any()) } throws IllegalStateException("disk full")
        assertFailsWith<IllegalStateException> {
            service(journal).release(FrontierOwner(FrontierOwnerType.PERSONAL, owner))
        }
        verify(exactly = 0) { claims.remove(any()) }
    }

    @Test fun `guild destruction includes different placers but excludes their personal claims`() {
        val guild = UUID.randomUUID()
        val a = claim(UUID.randomUUID(), guild)
        val b = claim(UUID.randomUUID(), guild)
        every { claims.getByTeam(guild) } returns setOf(a, b)
        every { claims.getById(a.id) } returns a
        every { claims.getById(b.id) } returns b
        every { claims.remove(any()) } returns true
        service(FrontierReleaseJournalFiles(directory)).release(FrontierOwner(FrontierOwnerType.GUILD, guild))
        verify(exactly = 1) { claims.remove(a.id) }
        verify(exactly = 1) { claims.remove(b.id) }
        verify(exactly = 0) { claims.getByPlayer(any()) }
    }

    @Test fun `siege retry after release completion never resnapshots newly acquired land`() {
        val owner = FrontierOwner(FrontierOwnerType.PERSONAL, UUID.randomUUID())
        val original = claim(owner.id)
        val later = claim(owner.id)
        val remaining = mutableMapOf(original.id to original)
        every { claims.getByPlayer(owner.id) } answers { remaining.values.toSet() }
        every { claims.getById(any()) } answers { remaining[firstArg()] }
        every { claims.remove(any()) } answers { remaining.remove(firstArg()) != null }
        val release = service(FrontierReleaseJournalFiles(directory.resolve("release")))
        val disk = FrontierSiegeStoreFiles(directory.resolve("siege"))
        val failingStore = object : FrontierSiegeStore by disk {
            override fun save(record: FrontierSiegeRecord) {
                if (record.completedAt != null) error("crash before completion save")
                disk.save(record)
            }
        }
        val schedule = FrontierRaidSchedule(ZoneId.of("UTC"), LocalTime.of(20, 0), Duration.ofHours(1))
        val now = Instant.parse("2026-11-09T20:00:00Z")
        val defeat: (FrontierSiegeRecord) -> Unit = { release.release(requireNotNull(it.release)) }
        assertFailsWith<IllegalStateException> {
            FrontierSiege(schedule, failingStore, release::snapshot, defeat, requiredHits = 1)
                .hit(owner, "anchor", false, now)
        }
        assertTrue(remaining.isEmpty())
        remaining[later.id] = later
        FrontierSiege(schedule, disk, release::snapshot, defeat).recover(now.plusSeconds(7200))
        assertEquals(setOf(later.id), remaining.keys)
        verify(exactly = 1) { claims.getByPlayer(owner.id) }
        verify(exactly = 0) { claims.remove(later.id) }
        assertEquals(now.plusSeconds(7200), disk.get(owner)!!.completedAt)
    }
    @Test fun `twenty hit raids release guild and solo land independently and cooldown survives restart`() {
        for (type in FrontierOwnerType.entries) {
            val owner = FrontierOwner(type, UUID.randomUUID())
            val target = claim(owner.id, if (type == FrontierOwnerType.GUILD) owner.id else null)
            val unrelated = claim(UUID.randomUUID())
            val remaining = mutableMapOf(target.id to target, unrelated.id to unrelated)
            every { claims.getByPlayer(owner.id) } answers { remaining.values.filter { it.playerId == owner.id }.toSet() }
            every { claims.getByTeam(owner.id) } answers { remaining.values.filter { it.teamId == owner.id }.toSet() }
            every { claims.getById(any()) } answers { remaining[firstArg()] }
            every { claims.remove(any()) } answers { remaining.remove(firstArg()) != null }
            val release = service(FrontierReleaseJournalFiles(directory.resolve("release-$type")))
            val schedule = FrontierRaidSchedule(ZoneId.of("UTC"), LocalTime.of(20, 0), Duration.ofHours(1))
            fun restarted() = FrontierSiege(schedule, FrontierSiegeStoreFiles(directory.resolve("siege-$type")),
                release::snapshot, { release.release(requireNotNull(it.release)) })
            val start = Instant.parse("2026-11-09T20:00:00Z")
            assertEquals(FrontierSiegeResult.Protected, restarted().hit(owner, "objective", false, start.minusSeconds(1)))
            assertEquals(FrontierSiegeResult.Defender, restarted().hit(owner, "objective", true, start))
            repeat(19) { index ->
                assertEquals(FrontierSiegeResult.Progress(index + 1, 20), restarted().hit(owner, "objective", false, start.plusSeconds(index * 2L)))
                assertTrue(remaining.containsKey(target.id))
                kotlin.test.assertFalse(restarted().canReplace(owner, start.plusSeconds(index * 2L + 1)))
            }
            val defeated = start.plusSeconds(38)
            assertEquals(FrontierSiegeResult.Defeated, restarted().hit(owner, "objective", false, defeated))
            assertEquals(setOf(unrelated.id), remaining.keys)
            kotlin.test.assertFalse(restarted().canReplace(owner, defeated.plusSeconds(1799)))
            assertTrue(restarted().canReplace(owner, defeated.plusSeconds(1800)))
            restarted().recover(defeated.plusSeconds(1801))
            assertEquals(setOf(unrelated.id), remaining.keys)
        }
    }
}
