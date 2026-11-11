package net.lumalyte.lg.interaction.listeners

import net.lumalyte.lg.domain.entities.Claim
import net.lumalyte.lg.domain.entities.Guild
import net.lumalyte.lg.domain.entities.GuildVaultLocation
import net.lumalyte.lg.domain.entities.VaultStatus
import net.lumalyte.lg.application.services.FrontierRaidClaim
import net.lumalyte.lg.domain.values.FrontierChunk
import net.lumalyte.lg.domain.values.Position3D
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
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

    @Test
    fun `guild chest icon requires available vault in this exact world and chunk`() {
        val chunk = FrontierChunk(world, -2, 3)
        val vault = GuildVaultLocation(world, -17, 74, 48)
        val g = Guild(guild, "TestGuild", createdAt = Instant.now(),
            vaultChestLocation = vault, vaultStatus = VaultStatus.AVAILABLE)
        assertTrue(FrontierMapVaults.guildAt(g, chunk))
        assertEquals(MapSymbol.VAULT, MapCell.claimed(MapTile.OWN, true).symbol)
        assertEquals(MapTile.OWN.color, MapCell.claimed(MapTile.OWN, true).tile.color)
        assertFalse(FrontierMapVaults.guildAt(g.copy(vaultStatus = VaultStatus.UNAVAILABLE), chunk))
        assertFalse(FrontierMapVaults.guildAt(g.copy(vaultChestLocation = null), chunk))
        assertFalse(FrontierMapVaults.guildAt(g, chunk.copy(worldId = UUID.randomUUID())))
        assertFalse(FrontierMapVaults.guildAt(g, chunk.copy(x = -1)))
    }

    @Test
    fun `solo barrel icon only appears at primary anchor chunk`() {
        val barrel = Claim(world, player, null, Position3D(-1, 64, -16), "Solo")
        val chunk = FrontierChunk(world, -1, -1)
        assertTrue(FrontierMapVaults.soloAt(barrel, true, chunk))
        assertFalse(FrontierMapVaults.soloAt(barrel, false, chunk))
        assertFalse(FrontierMapVaults.soloAt(barrel, true, chunk.copy(x = 0)))
        assertFalse(FrontierMapVaults.soloAt(barrel, true, chunk.copy(worldId = UUID.randomUUID())))
        assertFalse(FrontierMapVaults.soloAt(barrel.copy(teamId = guild), true, chunk))
    }

    @Test
    fun `war banner icon replaces only a bound raid square without changing its color`() {
        val staging = FrontierRaidClaim(guild, enemy, FrontierChunk(world, 0, 0),
            createdAt = 1L, windowEnd = 10_000L, graceEnd = 120L)
        val before = MapCell.raid(staging)
        val after = MapCell.raid(staging.copy(bannerId = UUID.randomUUID()))
        assertEquals(MapSymbol.SQUARE, before.symbol)
        assertEquals(MapSymbol.WAR_BANNER, after.symbol)
        assertEquals(MapTile.RAID, before.tile)
        assertEquals(MapTile.RAID, after.tile)
    }

    private fun claim(owner: UUID, team: UUID?) =
        Claim(world, owner, team, Position3D(0, 64, 0), "Territory")
}
