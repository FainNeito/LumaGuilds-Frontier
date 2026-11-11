package net.lumalyte.lg.infrastructure.services

import io.mockk.*
import net.lumalyte.lg.application.persistence.*
import net.lumalyte.lg.domain.entities.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class FrontierRosterTest {
    private val members=mockk<MemberRepository>(relaxed=true)
    private val ranks=mockk<RankRepository>(relaxed=true)
    private val guilds=mockk<GuildRepository>(relaxed=true)
    private val service=MemberServiceBukkit(members,ranks,guilds,mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true))
    private val guild=UUID.randomUUID(); private val player=UUID.randomUUID(); private val rankId=UUID.randomUUID()
    @Test fun `configured cap overrides progression and counts all stored members`() {
        service.frontierMemberLimit=6
        assertEquals(6,service.getMemberLimit(guild))
        every { guilds.getById(guild) } returns mockk(relaxed=true)
        every { ranks.getById(rankId) } returns mockk<Rank> { every { guildId } returns guild; every { priority } returns 1 }
        every { members.getByGuild(guild) } returns (1..6).map { mockk<Member>() }.toSet()
        assertNull(service.addMember(player,guild,rankId))
        verify(exactly=0) { members.add(any()) }
        service.frontierMemberLimit=4
        assertEquals(4,service.getMemberLimit(guild))
    }
    @Test fun `raid lock blocks joining and kicks before writes`() {
        service.frontierRosterLocked={true}
        assertNull(service.addMember(player,guild,rankId))
        assertFalse(service.removeMember(player,guild,UUID.randomUUID()))
        verify(exactly=0) { members.add(any()) }
        verify(exactly=0) { members.remove(any(),any()) }
    }
    @Test fun `voluntary departure still reaches removal during raids`() {
        service.frontierRosterLocked={true}
        every { members.isPlayerInGuild(player,guild) } returns true
        every { members.getByPlayerAndGuild(player,guild) } returns Member(player,guild,rankId,java.time.Instant.now())
        every { ranks.getById(rankId) } returns mockk<Rank> { every { guildId } returns guild; every { priority } returns 1 }
        every { members.remove(player,guild) } returns false
        service.removeMember(player,guild,player)
        verify(exactly=1) { members.remove(player,guild) }
    }
}
