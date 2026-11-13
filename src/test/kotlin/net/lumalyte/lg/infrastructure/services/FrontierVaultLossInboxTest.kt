package net.lumalyte.lg.infrastructure.services

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class FrontierVaultLossInboxTest {
    @TempDir lateinit var directory: Path
    @Test fun `notices persist for each recipient and acknowledge independently`() {
        val file = directory.resolve("inbox.yml").toFile()
        val first = UUID.randomUUID(); val second = UUID.randomUUID()
        val inbox = FrontierVaultLossInbox(file)
        inbox.record(setOf(first, second), "Red Keep", "Raider", "2026-11-13T12:00:00Z")
        inbox.record(setOf(first), "Red Keep", null, "2026-11-14T12:00:00Z")
        val reloaded = FrontierVaultLossInbox(file)
        assertEquals(2, reloaded.pending(first).size)
        assertEquals("Raider", reloaded.pending(second).single().attacker)
        assertNull(reloaded.pending(first).last().attacker)
        reloaded.acknowledge(first, reloaded.pending(first).first().id)
        val again = FrontierVaultLossInbox(file)
        assertEquals(1, again.pending(first).size)
        assertEquals(1, again.pending(second).size)
        assertEquals("2026-11-14T12:00:00Z", again.pending(first).single().time)
    }
}
