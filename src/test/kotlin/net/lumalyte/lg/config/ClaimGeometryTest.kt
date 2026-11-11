package net.lumalyte.lg.config

import net.lumalyte.lg.domain.values.Area
import net.lumalyte.lg.domain.values.Position2D
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ClaimGeometryTest {
    @Test fun `chunk creation includes entire negative chunk`() {
        val area = ClaimGeometry.CHUNKS.initialArea(Position2D(-1, -17), 7)
        assertEquals(Area(Position2D(-16, -32), Position2D(-1, -17)), area)
        assertEquals(256, area.getBlockCount())
    }

    @Test fun `expansion crossing origin covers all selected chunks`() {
        val selection = Area(Position2D(-1, -1), Position2D(16, 16))
        val normalized = ClaimGeometry.CHUNKS.normalize(selection)
        assertEquals(Area(Position2D(-16, -16), Position2D(31, 31)), normalized)
        assertEquals(normalized, ClaimGeometry.CHUNKS.normalize(normalized))
    }

    @Test fun `SMP geometry retains configured size and exact selection`() {
        assertEquals(Area(Position2D(-3, -3), Position2D(3, 3)),
            ClaimGeometry.BLOCKS.initialArea(Position2D(0, 0), 7))
        val selection = Area(Position2D(1, 2), Position2D(4, 9))
        assertEquals(selection, ClaimGeometry.BLOCKS.normalize(selection))
    }
}
