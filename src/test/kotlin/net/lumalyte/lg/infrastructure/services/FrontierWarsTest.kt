package net.lumalyte.lg.infrastructure.services

import io.mockk.*
import net.lumalyte.lg.application.persistence.ClaimRepository
import net.lumalyte.lg.application.services.*
import net.lumalyte.lg.config.FrontierConfig
import net.lumalyte.lg.domain.entities.*
import net.lumalyte.lg.domain.values.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.*
import java.util.UUID
import kotlin.test.*

class FrontierWarsTest {
    @TempDir lateinit var directory:Path
    private val attacker=UUID.randomUUID();private val defender=UUID.randomUUID();private val actor=UUID.randomUUID()
    private val claims=mockk<ClaimRepository>()
    private val guilds=mockk<GuildService>()
    private val members=mockk<MemberService>(relaxed=true)
    private val relations=mockk<RelationService>()
    private var now=Instant.parse("2026-11-11T19:00:00Z")
    private val config=FrontierConfig(enabled=true,schedule=FrontierRaidSchedule(ZoneOffset.UTC,LocalTime.of(20,0),Duration.ofHours(1)))
    private val notices=mutableListOf<String>()
    init {
        for(id in listOf(attacker,defender)) {
            every { guilds.getGuild(id) } returns Guild(id,id.toString().take(8),createdAt=Instant.EPOCH)
            every { claims.getByTeam(id) } returns setOf(Claim(UUID.randomUUID(),actor,id,Position3D(0,64,0),"Land"))
            every { members.getMember(actor,id) } returns Member(actor,id,UUID.randomUUID(),Instant.EPOCH)
        }
        every { members.hasPermission(any(),any(),RankPermission.DECLARE_WAR) } returns true
        every { relations.getRelationType(attacker,defender) } returns RelationType.ENEMY
    }
    private fun service()=FrontierWars(directory.resolve("wars.yml").toFile(),config,claims,guilds,members,relations,{ _,text->notices.add(text) },{ now })
    @Test fun `declaration survives restart and reserves both guilds`() {
        val service=service();service.declare(actor,attacker,defender)
        assertNotNull(service().current(defender))
        service.declare(actor,attacker,defender)
        assertEquals(1,service.records().size)
    }
    @Test fun `opposing vault destruction during window records one durable victory`() {
        val service=service();service.declare(actor,attacker,defender);now=now.plusSeconds(3600)
        service.vaultFallen(FrontierOwner(FrontierOwnerType.GUILD,defender),setOf(attacker))
        service.vaultFallen(FrontierOwner(FrontierOwnerType.GUILD,defender),setOf(attacker))
        assertEquals(attacker,service().records().single().winner)
        assertNull(service.current(defender));assertEquals(2,notices.size)
    }
    @Test fun `third party destruction does not award opponent a victory`() {
        val service=service();service.declare(actor,attacker,defender);now=now.plusSeconds(3600)
        service.vaultFallen(FrontierOwner(FrontierOwnerType.GUILD,defender),setOf(UUID.randomUUID()))
        assertNull(service.records().single().winner);assertNotNull(service.records().single().reason)
    }
    @Test fun `outside window destruction cannot win and expiry draws`() {
        val service=service();service.declare(actor,attacker,defender)
        service.vaultFallen(FrontierOwner(FrontierOwnerType.GUILD,defender),setOf(attacker))
        assertNotNull(service.current(defender));now=now.plusSeconds(7200);service.tick()
        assertNull(service.current(defender));assertNull(service.records().single().winner)
    }
    @Test fun `peace requires both guilds and surrender checks current authority`() {
        val service=service();service.declare(actor,attacker,defender)
        assertTrue(service.peace(actor,attacker));assertNotNull(service.current(defender))
        assertTrue(service.peace(actor,defender));assertNull(service.current(defender))
        assertNull(service.records().single().winner)
        every { members.getMember(actor,attacker) } returns null
        assertFalse(service.surrender(actor,attacker))
    }
    @Test fun `failed persistence does not publish declaration or announcement`() {
        val service=service();java.nio.file.Files.createDirectory(directory.resolve("wars.yml.tmp"))
        assertFails { service.declare(actor,attacker,defender) }
        assertTrue(service.records().isEmpty());assertTrue(notices.isEmpty())
    }
    @Test fun `packing founding territory before battle forfeits war`() {
        val service=service();service.declare(actor,attacker,defender)
        every { claims.getByTeam(defender) } returns emptySet()
        service.tick();assertEquals(attacker,service().records().single().winner)
    }
}
