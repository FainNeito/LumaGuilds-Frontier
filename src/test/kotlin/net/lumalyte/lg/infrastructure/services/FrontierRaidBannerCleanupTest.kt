package net.lumalyte.lg.infrastructure.services

import io.mockk.every
import io.mockk.mockk
import net.lumalyte.lg.application.services.FrontierRaidClaim
import net.lumalyte.lg.application.services.WarBannerService
import net.lumalyte.lg.common.PluginKeys
import net.lumalyte.lg.domain.entities.WarBannerState
import net.lumalyte.lg.domain.values.FrontierChunk
import net.lumalyte.lg.domain.values.Position3D
import org.bukkit.Material
import org.bukkit.block.Banner
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.util.UUID
import kotlin.test.*

class FrontierRaidBannerCleanupTest {
    private lateinit var server: ServerMock

    @BeforeEach fun setup() {
        server = MockBukkit.mock()
        PluginKeys.initialize(MockBukkit.createMockPlugin("LumaGuilds"))
    }

    @AfterEach fun teardown() { MockBukkit.unmock() }

    @Test fun `hour end deactivates core and removes the exact tagged physical banner`() {
        val world = server.addSimpleWorld("frontier")
        val guild = UUID.randomUUID()
        val id = UUID.randomUUID()
        val block = world.getBlockAt(17, 70, 2)
        block.type = Material.WHITE_BANNER
        val banner = block.state as Banner
        banner.persistentDataContainer.set(PluginKeys.WAR_BANNER_ID, PersistentDataType.STRING, id.toString())
        banner.update(true, false)

        val state = WarBannerState(guild, id, world.uid, 17, 70, 2,
            UUID.randomUUID(), UUID.randomUUID(), 1_000, 60_000, 86_400_000, true)
        var active = true
        val core = mockk<WarBannerService> {
            every { state(guild) } answers { state.copy(active = active) }
            every { destroyAt(world.uid, 17, 70, 2) } answers {
                active = false
                state
            }
        }
        val claim = FrontierRaidClaim(guild, UUID.randomUUID(), FrontierChunk(world.uid, 1, 0),
            1_000, 60_000, 60_000, id, 60_000, Position3D(17, 70, 2))
        assertTrue(FrontierRaidBannerCleanup(core) { world }.remove(claim))
        assertFalse(active)
        assertEquals(Material.AIR, block.type)
    }

    @Test fun `restart cleanup removes tagged banner even if its SQL state has disappeared`() {
        val world = server.addSimpleWorld("frontier")
        val guild = UUID.randomUUID()
        val id = UUID.randomUUID()
        val block = world.getBlockAt(17, 70, 2)
        block.type = Material.WHITE_BANNER
        val banner = block.state as Banner
        banner.persistentDataContainer.set(PluginKeys.WAR_BANNER_ID, PersistentDataType.STRING, id.toString())
        banner.update(true, false)
        val claim = FrontierRaidClaim(guild, UUID.randomUUID(), FrontierChunk(world.uid, 1, 0),
            1_000, 60_000, 60_000, id, 60_000, Position3D(17, 70, 2))
        val core = mockk<WarBannerService> { every { state(guild) } returns null }
        assertFalse(FrontierRaidBannerCleanup(core) { null }.remove(claim))
        assertEquals(Material.WHITE_BANNER, block.type)
        assertTrue(FrontierRaidBannerCleanup(core) { world }.remove(claim))
        assertEquals(Material.AIR, block.type)
    }

    @Test fun `cleanup never removes a different banner occupying the old position`() {
        val world = server.addSimpleWorld("frontier")
        val guild = UUID.randomUUID()
        val block = world.getBlockAt(17, 70, 2)
        block.type = Material.WHITE_BANNER
        val banner = block.state as Banner
        banner.persistentDataContainer.set(PluginKeys.WAR_BANNER_ID, PersistentDataType.STRING, UUID.randomUUID().toString())
        banner.update(true, false)
        val claim = FrontierRaidClaim(guild, UUID.randomUUID(), FrontierChunk(world.uid, 1, 0),
            1_000, 60_000, 60_000, UUID.randomUUID(), 60_000, Position3D(17, 70, 2))
        val core = mockk<WarBannerService> { every { state(guild) } returns null }
        assertTrue(FrontierRaidBannerCleanup(core) { world }.remove(claim))
        assertEquals(Material.WHITE_BANNER, block.type)
    }
}
