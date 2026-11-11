package net.lumalyte.lg.application.services

import io.mockk.*
import net.lumalyte.lg.application.persistence.ClaimRepository
import net.lumalyte.lg.application.persistence.GuildRepository
import net.lumalyte.lg.config.MainConfig
import net.lumalyte.lg.domain.entities.*
import net.lumalyte.lg.domain.values.Position3D
import net.lumalyte.lg.infrastructure.persistence.claims.FrontierSiegeStoreFiles
import net.lumalyte.lg.infrastructure.services.FrontierObjectives
import org.bukkit.Location
import org.bukkit.World
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class FrontierObjectiveRecoveryTest {
    @TempDir lateinit var directory: Path
    private val claims = mockk<ClaimRepository>()
    private val guilds = mockk<GuildRepository>()
    private val vaults = mockk<GuildVaultService>()
    private val release = mockk<FrontierClaimRelease>()

    @Test fun `vault failure retains defeat and recovery preserves inventory before releasing exact plan`() {
        val owner = FrontierOwner(FrontierOwnerType.GUILD, UUID.randomUUID())
        val world = mockk<World> { every { uid } returns UUID.randomUUID() }
        val location = Location(world, 3.0, 64.0, 3.0)
        val guild = Guild(owner.id, "Raid", createdAt = Instant.now(), vaultStatus = VaultStatus.AVAILABLE)
        val store = FrontierSiegeStoreFiles(directory)
        val plan = FrontierReleasePlan(UUID.randomUUID(), owner, setOf(UUID.randomUUID()))
        val record = FrontierSiegeRecord(owner, FrontierObjectives.key(location), Instant.now(), 20, Instant.now(), plan)
        store.save(record)
        every { guilds.getById(owner.id) } returns guild
        every { vaults.getVaultLocation(guild) } returns location
        every { vaults.removeVaultChest(guild, false) } returns VaultResult.Failure("storage unavailable")
        fun service() = FrontierObjectives(MainConfig(), FrontierSiegeStoreFiles(directory), release, claims, guilds) { vaults }
        assertFailsWith<IllegalStateException> { service().recover() }
        verify(exactly = 0) { release.release(any<FrontierReleasePlan>()) }
        assertNull(store.get(owner)!!.completedAt)
        every { vaults.removeVaultChest(guild, false) } returns VaultResult.Success(guild.copy(vaultStatus = VaultStatus.UNAVAILABLE))
        every { release.release(plan) } returns plan
        service().recover()
        assertNotNull(store.get(owner)!!.completedAt)
        verifyOrder { vaults.removeVaultChest(guild, false); release.release(plan) }
        verify(exactly = 0) { vaults.removeVaultChest(any(), true) }
        service().recover()
        verify(exactly = 1) { release.release(plan) }
    }

    @Test fun `oldest personal claim defines solo anchor without counting guild land`() {
        val player = UUID.randomUUID()
        val old = Claim(UUID.randomUUID(), player, null, Position3D(0, 64, 0), "Old").copy(creationTime = Instant.EPOCH)
        val newer = old.copy(id = UUID.randomUUID(), creationTime = Instant.EPOCH.plusSeconds(1))
        val guild = old.copy(id = UUID.randomUUID(), teamId = UUID.randomUUID(), creationTime = Instant.EPOCH.minusSeconds(1))
        every { claims.getByPlayer(player) } returns setOf(newer, guild, old)
        val service = FrontierObjectives(MainConfig(), FrontierSiegeStoreFiles(directory), release, claims, guilds) { vaults }
        assertTrue(service.primaryAnchor(old))
        assertFalse(service.primaryAnchor(newer))
        assertFalse(service.primaryAnchor(guild))
    }

    @Test fun `solo defeat removes recorded bell before releasing personal snapshot`() {
        val player = UUID.randomUUID()
        val owner = FrontierOwner(FrontierOwnerType.PERSONAL, player)
        val world = mockk<World> { every { uid } returns UUID.randomUUID() }
        val block = mockk<org.bukkit.block.Block>(relaxed = true) { every { type } returns org.bukkit.Material.BELL }
        val claim = Claim(world.uid, player, null, Position3D(2, 64, 2), "Solo")
        val location = Location(world, 2.0, 64.0, 2.0)
        val plan = FrontierReleasePlan(UUID.randomUUID(), owner, setOf(claim.id))
        val store = FrontierSiegeStoreFiles(directory)
        store.save(FrontierSiegeRecord(owner, FrontierObjectives.key(location), Instant.now(), 20, Instant.now(), plan))
        every { claims.getById(claim.id) } returns claim
        every { world.getBlockAt(2, 64, 2) } returns block
        every { release.release(plan) } returns plan
        mockkStatic(org.bukkit.Bukkit::class)
        try {
            val worldId = world.uid
            every { org.bukkit.Bukkit.getWorld(worldId) } returns world
            FrontierObjectives(MainConfig(), store, release, claims, guilds) { vaults }.recover()
            verifyOrder { block.setType(org.bukkit.Material.AIR, false); release.release(plan) }
            verify(exactly = 0) { vaults.removeVaultChest(any(), any()) }
            assertNotNull(store.get(owner)!!.completedAt)
        } finally {
            unmockkStatic(org.bukkit.Bukkit::class)
        }
    }
}
