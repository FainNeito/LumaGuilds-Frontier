package net.lumalyte.lg.domain.values

import java.util.UUID

/** World-qualified ownership key; height never changes the owner of a chunk. */
data class FrontierChunk(val worldId: UUID, val x: Int, val z: Int) {
    companion object {
        fun atBlock(worldId: UUID, blockX: Int, blockZ: Int): FrontierChunk =
            FrontierChunk(worldId, Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16))
    }
}
