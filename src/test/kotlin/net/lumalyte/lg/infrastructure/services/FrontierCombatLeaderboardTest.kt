package net.lumalyte.lg.infrastructure.services

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class FrontierCombatLeaderboardTest {
    @TempDir lateinit var directory: Path
    @Test fun `scores persist and rankings are deterministic`() {
        val path=directory.resolve("scores.yml").toFile()
        val first=UUID.fromString("00000000-0000-0000-0000-000000000001")
        val second=UUID.fromString("00000000-0000-0000-0000-000000000002")
        val board=FrontierCombatLeaderboard(path)
        board.kill(first,second); board.kill(first,second); board.kill(second,first)
        board.kill(first,first)
        board.vault(first,second); board.vault(first,first)
        val reloaded=FrontierCombatLeaderboard(path)
        assertEquals(listOf(first to 2L,second to 1L),reloaded.kills())
        assertEquals(listOf(first to 1L),reloaded.vaults())
    }
}
