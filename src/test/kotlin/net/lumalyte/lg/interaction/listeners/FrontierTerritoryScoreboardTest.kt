package net.lumalyte.lg.interaction.listeners

import net.lumalyte.lg.domain.entities.Claim
import net.lumalyte.lg.domain.values.Position3D
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class FrontierTerritoryScoreboardTest {
    private val world = UUID.randomUUID()
    private val player = UUID.randomUUID()
    private val guild = UUID.randomUUID()
    private val ally = UUID.randomUUID()
    private val enemy = UUID.randomUUID()

    @Test
    fun `grid is 11 square with player at center and north at top`() {
        val grid = FrontierMapGrid.around(-10, 20) { x, z -> x to z }
        assertEquals(11, grid.size)
        assertTrue(grid.all { it.size == 11 })
        assertEquals(-15 to 15, grid.first().first())
        assertEquals(-10 to 20, grid[5][5])
        assertEquals(-5 to 25, grid.last().last())
        assertEquals(-10 to 15, grid.first()[5]) // North decreases Z.
        assertEquals(-15 to 20, grid[5].first()) // West decreases X.
    }

    @Test
    fun `movement refreshes exactly on chunk or world changes including negative coordinates`() {
        assertFalse(FrontierMapGrid.changedChunk(world, 0, 0, world, 15, 15))
        assertTrue(FrontierMapGrid.changedChunk(world, 15, 0, world, 16, 0))
        assertTrue(FrontierMapGrid.changedChunk(world, 0, 0, world, -1, 0))
        assertFalse(FrontierMapGrid.changedChunk(world, -16, -16, world, -1, -1))
        assertTrue(FrontierMapGrid.changedChunk(world, -16, 0, world, -17, 0))
        assertTrue(FrontierMapGrid.changedChunk(world, 4, 4, UUID.randomUUID(), 4, 4))
    }

    @Test
    fun `own personal or guild claims win over overlap and allies`() {
        val ownPersonal = claim(player, null)
        val ownGuild = claim(UUID.randomUUID(), guild)
        val allyGuild = claim(UUID.randomUUID(), ally)
        val enemyGuild = claim(UUID.randomUUID(), enemy)
        fun tile(vararg owners: Claim): MapTile? = FrontierMapOwnership.classify(
            owners.asList(), player, setOf(guild)) { it == ally }

        assertEquals(null, tile())
        assertEquals(MapTile.OWN, tile(ownPersonal))
        assertEquals(MapTile.OWN, tile(ownGuild))
        assertEquals(MapTile.OWN, tile(enemyGuild, ownGuild))
        assertEquals(MapTile.ALLY, tile(allyGuild))
        assertEquals(MapTile.ALLY, tile(enemyGuild, allyGuild))
        assertEquals(MapTile.FOREIGN, tile(enemyGuild))
        assertEquals(MapTile.FOREIGN, tile(claim(UUID.randomUUID(), null)))
    }

    private fun claim(owner: UUID, team: UUID?) =
        Claim(world, owner, team, Position3D(0, 64, 0), "Territory")
}
