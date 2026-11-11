package net.lumalyte.lg.config

import net.lumalyte.lg.domain.values.Area

/** Public server land: reserves chunks without a player guild, vault or protection flags. */
data class FrontierServerTerritory(
    val name: String = "Watcher's Ruin",
    val minChunkX: Int = -8, val maxChunkX: Int = 7,
    val minChunkZ: Int = -8, val maxChunkZ: Int = 7,
) {
    init {
        require(name.isNotBlank() && name.length <= 50)
        require(minChunkX <= maxChunkX && minChunkZ <= maxChunkZ)
        require(listOf(minChunkX,maxChunkX,minChunkZ,maxChunkZ).all { it in -1875..1874 })
    }
    fun contains(x: Int,z: Int) = (x shr 4) in minChunkX..maxChunkX && (z shr 4) in minChunkZ..maxChunkZ
    fun overlaps(area: Area): Boolean =
        (area.lowerPosition2D.x shr 4) <= maxChunkX && (area.upperPosition2D.x shr 4) >= minChunkX &&
        (area.lowerPosition2D.z shr 4) <= maxChunkZ && (area.upperPosition2D.z shr 4) >= minChunkZ
}
