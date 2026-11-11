package net.lumalyte.lg.application.services

import co.aikar.idb.Database
import io.mockk.*
import net.lumalyte.lg.domain.entities.Partition
import net.lumalyte.lg.domain.values.*
import net.lumalyte.lg.infrastructure.persistence.partitions.PartitionRepositorySQLite
import net.lumalyte.lg.infrastructure.persistence.storage.Storage
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.UUID
import kotlin.test.*

class FrontierPartitionPersistenceTest {
    @Test fun `failed partition reassignment keeps old ownership and retry moves it safely`() {
        val database=mockk<Database>(relaxed=true)
        every { database.getResults(any(),*anyVararg()) } returns emptyList()
        every { database.executeUpdate(any(),*anyVararg()) } returns 1
        val storage=mockk<Storage<Database>> { every { connection } returns database }
        val repo=PartitionRepositorySQLite(storage)
        val old=Partition(UUID.randomUUID(),Area(Position2D(0,0),Position2D(15,15)))
        assertTrue(repo.add(old))
        val moved=old.copy(claimId=UUID.randomUUID())
        every { database.executeUpdate(match { it.startsWith("UPDATE") },*anyVararg()) } throws SQLException("write failed")
        assertFails { repo.update(moved) }
        assertEquals(setOf(old),repo.getByClaim(old.claimId))
        assertTrue(repo.getByClaim(moved.claimId).isEmpty())
        every { database.executeUpdate(match { it.startsWith("UPDATE") },*anyVararg()) } returns 1
        assertTrue(repo.update(moved))
        repo.removeByClaim(old.claimId)
        assertEquals(setOf(moved),repo.getByClaim(moved.claimId))
        assertEquals(setOf(moved),repo.getByChunk(Position2D(0,0)))
    }
}
