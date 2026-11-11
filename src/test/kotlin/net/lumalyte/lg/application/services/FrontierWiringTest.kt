package net.lumalyte.lg.application.services

import io.mockk.*
import net.lumalyte.lg.application.actions.claim.CreateClaim
import net.lumalyte.lg.application.actions.claim.anchor.MoveClaimAnchor
import net.lumalyte.lg.application.actions.claim.transfer.AcceptTransferRequest
import net.lumalyte.lg.application.persistence.ClaimRepository
import net.lumalyte.lg.application.results.claim.CreateClaimResult
import net.lumalyte.lg.application.results.claim.anchor.MoveClaimAnchorResult
import net.lumalyte.lg.application.results.claim.transfer.AcceptTransferRequestResult
import net.lumalyte.lg.config.*
import net.lumalyte.lg.domain.entities.Claim
import net.lumalyte.lg.domain.values.Position3D
import net.lumalyte.lg.infrastructure.persistence.claims.FrontierSiegeStoreFiles
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class FrontierWiringTest {
    @TempDir lateinit var directory: Path
    private val owner = FrontierOwner(FrontierOwnerType.PERSONAL, UUID.randomUUID())
    private val now = Instant.now()
    private fun claim() = Claim(UUID.randomUUID(), owner.id, null, Position3D(0, 64, 0), "Solo")
    private fun policy(): FrontierReplacementPolicy {
        val store = FrontierSiegeStoreFiles(directory)
        store.save(FrontierSiegeRecord(owner, "anchor", now.plusSeconds(3600), 1, now))
        return FrontierReplacementPolicy(MainConfig(frontier = FrontierConfig(enabled = true)), store)
    }

    @Test fun `creation and movement reject persisted siege lock before writing`() {
        val claims = mockk<ClaimRepository>()
        val current = claim()
        every { claims.getById(current.id) } returns current
        val guard = policy()
        val create = CreateClaim(claims, mockk(), mockk(), mockk(), mockk(), MainConfig(), guard)
        assertEquals(CreateClaimResult.RaidLocked, create.execute(owner.id, "New", current.position, current.worldId))
        val move = MoveClaimAnchor(claims, mockk(), mockk(), mockk(), mockk(), guard)
        assertEquals(MoveClaimAnchorResult.NoPermission, move.execute(current.id, owner.id, current.worldId, Position3D(16, 64, 0)))
        verify(exactly = 0) { claims.add(any()) }
        verify(exactly = 0) { claims.update(any()) }
    }

    @Test fun `transfer cannot move attacked land to another player`() {
        val claims = mockk<ClaimRepository>()
        val current = claim()
        every { claims.getById(current.id) } returns current
        val action = AcceptTransferRequest(claims, mockk(), mockk(), mockk(), MainConfig(), policy())
        assertEquals(AcceptTransferRequestResult.RaidLocked, action.execute(current.id, UUID.randomUUID(), "Escape"))
        verify(exactly = 0) { claims.updateIfOwnedBy(any(), any()) }
    }

    @Test fun `replacement policy preserves SMP and owner isolation and cooldown boundary`() {
        val store = FrontierSiegeStoreFiles(directory)
        val plan = FrontierReleasePlan(UUID.randomUUID(), owner, emptySet())
        store.save(FrontierSiegeRecord(owner, "anchor", now.plusSeconds(3600), 20, now, plan))
        val guard = FrontierReplacementPolicy(MainConfig(frontier = FrontierConfig(enabled = true)), store)
        assertFalse(guard.allows(owner, now.plusSeconds(86400)))
        assertTrue(guard.allows(FrontierOwner(FrontierOwnerType.GUILD, owner.id), now))
        assertTrue(FrontierReplacementPolicy(MainConfig(), store).allows(owner, now))
        store.save(store.get(owner)!!.copy(completedAt = now))
        assertFalse(guard.allows(owner, now.plusSeconds(1799)))
        assertTrue(guard.allows(owner, now.plusSeconds(1800)))
    }

    @Test fun `vault service rejects replacement without touching block or stored inventory`() {
        val server = org.mockbukkit.mockbukkit.MockBukkit.mock()
        try {
            val plugin = org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin()
            val world = server.addSimpleWorld("frontier")
            val location = org.bukkit.Location(world, 0.0, 64.0, 0.0)
            location.block.type = org.bukkit.Material.AIR
            val guild = net.lumalyte.lg.domain.entities.Guild(UUID.randomUUID(), "Rebuild", createdAt = now)
            val guildOwner = FrontierOwner(FrontierOwnerType.GUILD, guild.id)
            val store = FrontierSiegeStoreFiles(directory)
            store.save(FrontierSiegeRecord(guildOwner, "vault", now.plusSeconds(3600), 20, now,
                FrontierReleasePlan(UUID.randomUUID(), guildOwner, emptySet()), now))
            val guard = FrontierReplacementPolicy(MainConfig(frontier = FrontierConfig(enabled = true)), store)
            val guilds = mockk<net.lumalyte.lg.application.persistence.GuildRepository>()
            val inventory = mockk<net.lumalyte.lg.infrastructure.vault.VaultInventoryManager>()
            val service = net.lumalyte.lg.infrastructure.services.GuildVaultServiceBukkit(
                plugin, guilds, mockk(), mockk(), mockk(), inventory, mockk(), mockk(), null, mockk(), guard)
            assertIs<VaultResult.Failure>(service.placeVaultChest(guild, location, server.addPlayer()))
            assertEquals(org.bukkit.Material.AIR, location.block.type)
            verify(exactly = 0) { guilds.update(any()) }
            verify { inventory wasNot Called }
        } finally {
            org.mockbukkit.mockbukkit.MockBukkit.unmock()
        }
    }

    @Test fun `legacy guild conversion cannot bypass founding-vault ownership`() {
        val current = claim()
        val guild = net.lumalyte.lg.domain.entities.Guild(UUID.randomUUID(), "Guild", createdAt = now)
        val claims = mockk<ClaimRepository>()
        val guilds = mockk<GuildService>()
        val resolver = mockk<GuildRolePermissionResolver>(relaxed = true)
        val lookup = mockk<net.lumalyte.lg.application.actions.claim.GetClaimAtPosition>()
        every { claims.getById(current.id) } returns current
        every { guilds.getPlayerGuilds(owner.id) } returns setOf(guild)
        every { guilds.getGuild(guild.id) } returns guild
        val action = net.lumalyte.lg.application.actions.claim.ConvertClaimToGuild(claims, guilds, resolver,
            MainConfig(frontier = FrontierConfig(enabled = true)), getClaimAtPosition = lookup)
        assertEquals(net.lumalyte.lg.application.results.claim.ConvertClaimToGuildResult.RaidLocked,
            action.execute(current.id, owner.id))
        verify(exactly = 0) { claims.update(any()) }
        every { guilds.getGuild(guild.id) } returns guild.copy(
            vaultStatus = net.lumalyte.lg.domain.entities.VaultStatus.AVAILABLE,
            vaultChestLocation = net.lumalyte.lg.domain.entities.GuildVaultLocation(current.worldId, 1, 64, 1))
        every { lookup.execute(current.worldId, any()) } returns
            net.lumalyte.lg.application.results.claim.GetClaimAtPositionResult.Success(current)
        every { claims.update(any()) } returns true
        assertEquals(net.lumalyte.lg.application.results.claim.ConvertClaimToGuildResult.RaidLocked,
            action.execute(current.id, owner.id))
    }

    @Test fun `disabled progression rejects awards before accessing player activity or storage`() {
        val repository = mockk<net.lumalyte.lg.application.persistence.ExperienceAwardRepository>()
        val service = PermanentExperienceService(repository, mockk(), enabledProvider = { false })
        val source = net.lumalyte.lg.domain.values.ExperienceSource.ADMIN_BONUS
        val request = net.lumalyte.lg.domain.entities.ExperienceAwardRequest(owner.id, null, source, 10, now)
        val policy = net.lumalyte.lg.domain.values.ExperiencePolicy(source, source.defaultPool, 1, 0,
            net.lumalyte.lg.domain.values.CapPeriod.UNLIMITED, true)
        assertEquals(net.lumalyte.lg.domain.entities.ExperienceAwardResult.Rejected(
            net.lumalyte.lg.domain.entities.AwardRejection.SOURCE_DISABLED), service.award(request, policy))
        verify { repository wasNot Called }
    }

    @Test fun `bell creation cannot create overlapping ownership in the same chunk`() {
        val claims = mockk<ClaimRepository>()
        val partitions = mockk<net.lumalyte.lg.application.persistence.PartitionRepository>()
        val existing = claim().copy(position = Position3D(320, 64, 0), playerId = UUID.randomUUID())
        val partition = net.lumalyte.lg.domain.entities.Partition(existing.id,
            net.lumalyte.lg.domain.values.Area(net.lumalyte.lg.domain.values.Position2D(320, 0),
                net.lumalyte.lg.domain.values.Position2D(335, 15)))
        every { claims.getByPlayer(owner.id) } returns emptySet()
        every { claims.getByName(owner.id, any()) } returns null
        every { claims.getById(existing.id) } returns existing
        every { partitions.getByChunk(any()) } returns setOf(partition)
        val config = MainConfig(claimGeometry = ClaimGeometry.CHUNKS, frontier = FrontierConfig(enabled = true))
        val create = CreateClaim(claims, partitions, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), config,
            clock = { Instant.parse("2026-11-11T12:00:00Z") })
        assertEquals(CreateClaimResult.InvalidLocation, create.execute(owner.id, "Overlap", Position3D(321, 64, 1), existing.worldId))
        verify(exactly = 0) { claims.add(any()) }
    }
    @Test fun `raid hour rejects creation before territory writes with a fixed clock`() {
        val claims = mockk<ClaimRepository>()
        val create = CreateClaim(claims, mockk(), mockk(), mockk(), mockk(relaxed = true),
            MainConfig(frontier = FrontierConfig(enabled = true)),
            clock = { Instant.parse("2026-11-11T20:30:00Z") })
        assertEquals(CreateClaimResult.RaidLocked,
            create.execute(owner.id, "Locked", Position3D(320, 64, 0), UUID.randomUUID()))
        verify { claims wasNot Called }
    }

}
