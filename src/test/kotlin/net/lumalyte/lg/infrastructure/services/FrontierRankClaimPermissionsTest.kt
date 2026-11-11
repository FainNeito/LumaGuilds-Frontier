package net.lumalyte.lg.infrastructure.services

import io.mockk.*
import net.lumalyte.lg.application.services.RankService
import net.lumalyte.lg.domain.entities.*
import net.lumalyte.lg.domain.values.ClaimPermission
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class FrontierRankClaimPermissionsTest {
    @TempDir lateinit var directory: Path
    private val ranks = mockk<RankService>()
    private val guild = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val target = Rank(UUID.randomUUID(),guild,"Member",2)
    private val leader = Rank(UUID.randomUUID(),guild,"Owner",0)
    private fun store() = FrontierRankClaimPermissions(directory.resolve("ranks.yml").toFile(), ranks,
        { setOf(ClaimPermission.BUILD) }, {})
    init {
        every { ranks.getRank(target.id) } returns target
        every { ranks.getPlayerRank(actor,guild) } returns leader
    }
    @Test fun `empty permissions persist and survive rename`() {
        val store = store()
        assertTrue(store.toggle(actor,target.id,ClaimPermission.BUILD))
        assertEquals(emptySet(),store().get(target.copy(name="Recruit")))
    }
    @Test fun `equal rank and former members cannot edit`() {
        every { ranks.getPlayerRank(actor,guild) } returns target
        assertFalse(store().toggle(actor,target.id,ClaimPermission.BUILD))
        every { ranks.getPlayerRank(actor,guild) } returns null
        assertFalse(store().toggle(actor,target.id,ClaimPermission.BUILD))
    }
    @Test fun `officer cannot grant claim access they do not possess`() {
        every { ranks.getPlayerRank(actor,guild) } returns Rank(UUID.randomUUID(),guild,"Officer",1,
            setOf(RankPermission.MANAGE_RANKS,RankPermission.MANAGE_PERMISSIONS))
        assertFalse(store().toggle(actor,target.id,ClaimPermission.CONTAINER))
    }
    @Test fun `failed write leaves previous access unchanged`() {
        val store = store()
        java.nio.file.Files.createDirectory(directory.resolve("ranks.yml.tmp"))
        assertFails { store.toggle(actor,target.id,ClaimPermission.BUILD) }
        assertNull(store.get(target))
    }
}
