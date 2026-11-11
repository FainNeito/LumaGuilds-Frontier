package net.lumalyte.lg.application.services

import net.lumalyte.lg.domain.values.FrontierRaidSchedule
import net.lumalyte.lg.infrastructure.persistence.claims.FrontierSiegeStoreFiles
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.*
import java.util.UUID
import kotlin.test.*

class FrontierSiegeTest {
    @TempDir lateinit var directory: Path
    private val owner = FrontierOwner(FrontierOwnerType.PERSONAL, UUID.randomUUID())
    private val start = Instant.parse("2026-11-09T20:00:00Z")
    private val schedule = FrontierRaidSchedule(ZoneId.of("UTC"), LocalTime.of(20, 0), Duration.ofHours(1))
    private fun siege(defeat: (FrontierSiegeRecord) -> Unit = {}) = FrontierSiege(schedule,
        FrontierSiegeStoreFiles(directory), { setOf(UUID.fromString("00000000-0000-0000-0000-000000000001")) },
        defeat, requiredHits = 2)

    @Test fun `damage persists across restart and rapid hits do not count`() {
        assertEquals(FrontierSiegeResult.Progress(1, 2), siege().hit(owner, "anchor", false, start))
        assertEquals(FrontierSiegeResult.TooSoon, siege().hit(owner, "anchor", false, start.plusSeconds(1)))
        var defeated = 0
        val service = siege { defeated++ }
        assertEquals(FrontierSiegeResult.Defeated, service.hit(owner, "anchor", false, start.plusSeconds(2)))
        assertEquals(1, defeated)
        assertFalse(service.canReplace(owner, start.plusSeconds(3)))
        assertTrue(service.canReplace(owner, start.plusSeconds(1802)))
    }

    @Test fun `defenders and attempts outside window do not write progress`() {
        assertEquals(FrontierSiegeResult.Defender, siege().hit(owner, "anchor", true, start))
        assertEquals(FrontierSiegeResult.Protected, siege().hit(owner, "anchor", false, start.minusSeconds(1)))
        assertNull(FrontierSiegeStoreFiles(directory).get(owner))
    }

    @Test fun `pending defeat recovers exact snapshot outside raid window`() {
        siege().hit(owner, "anchor", false, start)
        assertFailsWith<IllegalStateException> {
            siege { error("vault storage unavailable") }.hit(owner, "anchor", false, start.plusSeconds(2))
        }
        val pending = FrontierSiegeStoreFiles(directory).get(owner)!!
        assertNotNull(pending.release)
        assertFalse(siege().canReplace(owner, start.plusSeconds(7200)))
        val recovered = mutableListOf<FrontierSiegeRecord>()
        val service = siege { recovered.add(it) }
        service.recover(start.plusSeconds(7200))
        service.recover(start.plusSeconds(7201))
        assertEquals(listOf(pending), recovered)
        assertEquals(start.plusSeconds(7200), FrontierSiegeStoreFiles(directory).get(owner)!!.completedAt)
    }

    @Test fun `unfinished damage resets on next raid window`() {
        siege().hit(owner, "anchor", false, start)
        assertEquals(FrontierSiegeResult.Progress(1, 2), siege().hit(owner, "anchor", false, start.plusSeconds(86400)))
    }

    @Test fun `damaged objective cannot be replaced or switched during its raid window`() {
        val service = siege()
        assertTrue(service.canReplace(owner, start))
        service.hit(owner, "anchor", false, start)
        assertFalse(siege().canReplace(owner, start.plusSeconds(3)))
        assertEquals(FrontierSiegeResult.DifferentObjective,
            siege().hit(owner, "replacement", false, start.plusSeconds(3)))
        assertEquals(1, FrontierSiegeStoreFiles(directory).get(owner)!!.hits)
        assertTrue(service.canReplace(owner, start.plusSeconds(3600)))
        assertEquals(FrontierSiegeResult.Progress(1, 2),
            siege().hit(owner, "replacement", false, start.plusSeconds(86400)))
    }
    @Test fun `TNT damage preserves interval and commits one defeat with cooldown`() {
        var defeats=0
        val store=FrontierSiegeStoreFiles(directory)
        val service=FrontierSiege(schedule,store,{ emptySet() },{ defeats++ },requiredHits=20)
        for (i in 0..3) assertEquals(FrontierSiegeResult.Progress((i+1)*4,20),service.hit(owner,"vault",false,start.plusSeconds(i*2L),4))
        assertEquals(FrontierSiegeResult.TooSoon,service.hit(owner,"vault",false,start.plusSeconds(7),4))
        assertEquals(FrontierSiegeResult.Defeated,service.hit(owner,"vault",false,start.plusSeconds(8),4))
        assertEquals(1,defeats)
        assertFalse(service.canReplace(owner,start.plusSeconds(1807)))
        assertTrue(service.canReplace(owner,start.plusSeconds(1808)))
    }}
