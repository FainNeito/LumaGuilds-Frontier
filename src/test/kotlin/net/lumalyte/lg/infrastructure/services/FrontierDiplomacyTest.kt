package net.lumalyte.lg.infrastructure.services

import io.mockk.*
import net.lumalyte.lg.application.persistence.*
import net.lumalyte.lg.application.services.MemberService
import net.lumalyte.lg.domain.entities.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class FrontierDiplomacyTest {
    @Test fun `zero alliance limit blocks requests and pending acceptance`() {
        val first=UUID.randomUUID(); val second=UUID.randomUUID(); val actor=UUID.randomUUID()
        val repository=mockk<RelationRepository>(relaxed=true)
        val members=mockk<MemberService>(relaxed=true)
        every { members.hasPermission(any(),any(),any()) } returns true
        val pending=Relation.create(guildA=first,guildB=second,type=RelationType.ALLY,status=RelationStatus.PENDING,requestingGuildId=first)
        every { repository.getById(pending.id) } returns pending
        val service=RelationServiceBukkit(repository,members,mockk(relaxed=true)).apply { allianceLimit=0 }
        assertNull(service.requestAlliance(first,second,actor))
        assertNull(service.acceptAlliance(pending.id,second,actor))
        verify(exactly=0) { repository.add(any()) }
        verify(exactly=0) { repository.update(any()) }
    }
    @Test fun `full alliance capacity blocks new requests and late acceptance`() {
        val first=UUID.randomUUID();val second=UUID.randomUUID();val actor=UUID.randomUUID()
        val repository=mockk<RelationRepository>(relaxed=true)
        val members=mockk<MemberService>(relaxed=true)
        every { members.hasPermission(any(),any(),any()) } returns true
        val full=setOf(Relation.create(guildA=first,guildB=UUID.randomUUID(),type=RelationType.ALLY),
            Relation.create(guildA=first,guildB=UUID.randomUUID(),type=RelationType.ALLY))
        every { repository.getByGuild(first) } returns full
        val pending=Relation.create(guildA=first,guildB=second,type=RelationType.ALLY,status=RelationStatus.PENDING,requestingGuildId=first)
        every { repository.getById(pending.id) } returns pending
        val service=RelationServiceBukkit(repository,members,mockk(relaxed=true)).apply { allianceLimit=2 }
        assertNull(service.requestAlliance(first,second,actor))
        assertNull(service.acceptAlliance(pending.id,second,actor))
        verify(exactly=0) { repository.update(any()) }
        verify(exactly=0) { repository.add(any()) }
    }
}
