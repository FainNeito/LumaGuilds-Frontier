package net.lumalyte.lg.domain.values

import java.util.UUID
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FrontierChunkTest {
    @Test fun `negative coordinates use Minecraft chunk boundaries`() {
        val world = UUID.randomUUID()
        for ((block, chunk) in mapOf(-17 to -2, -16 to -1, -1 to -1, 0 to 0, 15 to 0, 16 to 1)) {
            assertEquals(FrontierChunk(world, chunk, chunk), FrontierChunk.atBlock(world, block, block))
        }
        assertNotEquals(FrontierChunk(world, 0, 0), FrontierChunk(UUID.randomUUID(), 0, 0))
    }
}
