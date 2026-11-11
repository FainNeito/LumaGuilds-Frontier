package net.lumalyte.lg.config

import net.lumalyte.lg.domain.values.Area
import net.lumalyte.lg.domain.values.Position
import net.lumalyte.lg.domain.values.Position2D

enum class ClaimGeometry {
    BLOCKS,
    CHUNKS;

    fun initialArea(position: Position, size: Int): Area {
        if (this == CHUNKS) return normalize(Area(Position2D(position.x, position.z), Position2D(position.x, position.z)))
        return Area(
            Position2D(position.x - (size - 1) / 2, position.z - (size - 1) / 2),
            Position2D(position.x + size / 2, position.z + size / 2),
        )
    }

    /** Expand a selection to whole chunks before overlap, border, and capacity checks. */
    fun normalize(area: Area): Area {
        if (this == BLOCKS) return area
        fun lower(value: Int) = Math.floorDiv(value, 16) * 16
        fun upper(value: Int) = lower(value) + 15
        return Area(
            Position2D(lower(area.lowerPosition2D.x), lower(area.lowerPosition2D.z)),
            Position2D(upper(area.upperPosition2D.x), upper(area.upperPosition2D.z)),
        )
    }
}
