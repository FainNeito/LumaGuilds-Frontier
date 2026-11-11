package net.lumalyte.lg.application.services

import io.mockk.*
import net.lumalyte.lg.application.actions.claim.*
import net.lumalyte.lg.application.persistence.*
import net.lumalyte.lg.application.results.claim.*
import net.lumalyte.lg.config.*
import net.lumalyte.lg.domain.entities.Claim
import net.lumalyte.lg.domain.values.*
import org.junit.jupiter.api.Test
import java.time.*
import java.util.UUID
import kotlin.test.*

class FrontierRankAccessTest {
    @Test fun `raid window opens guild land without war but legacy solo waits for migration`() {
        val actor=UUID.randomUUID();val world=UUID.randomUUID()
        val claim=Claim(world,UUID.randomUUID(),UUID.randomUUID(),Position3D(0,64,0),"Keep")
        val lookup=mockk<GetClaimAtPosition>()
        every { lookup.execute(any(),any()) } returns GetClaimAtPositionResult.Success(claim)
        val config=MainConfig(frontier=FrontierConfig(enabled=true,guildOnlyClaims=true,schedule=FrontierRaidSchedule(ZoneOffset.UTC,LocalTime.now(ZoneOffset.UTC).minusMinutes(5),Duration.ofHours(1))))
        val action=IsPlayerActionAllowed(mockk(relaxed=true),mockk(relaxed=true),lookup,mockk(relaxed=true),mockk(relaxed=true),config)
        assertIs<IsPlayerActionAllowedResult.Allowed>(action.execute(actor,world,Position2D(0,0),PlayerActionType.BREAK_BLOCK))
        claim.teamId=null
        assertIs<IsPlayerActionAllowedResult.Denied>(action.execute(actor,world,Position2D(0,0),PlayerActionType.BREAK_BLOCK))
    }
    @Test fun `guild only creation rejects personal API requests before persistence`() {
        val claims=mockk<ClaimRepository>()
        val config=MainConfig(frontier=FrontierConfig(enabled=true,guildOnlyClaims=true))
        val create=CreateClaim(claims,mockk(),mockk(),mockk(),mockk(),config)
        assertIs<CreateClaimResult.InvalidLocation>(create.executeFrontier(UUID.randomUUID(),"Solo",Position3D(500,64,500),UUID.randomUUID()))
        verify { claims wasNot Called }
    }

    @Test fun `guild creator has no personal bypass but solo owner retains access`() {
        val actor=UUID.randomUUID();val world=UUID.randomUUID()
        val claim=Claim(world,actor,UUID.randomUUID(),Position3D(0,64,0),"Keep")
        val lookup=mockk<GetClaimAtPosition>()
        every { lookup.execute(any(),any()) } returns GetClaimAtPositionResult.Success(claim)
        val resolver=mockk<GuildRolePermissionResolver>()
        every { resolver.hasPermission(any(),any(),any()) } returns false
        val states=mockk<PlayerStateRepository>();every { states.get(any()) } returns null
        val config=MainConfig(frontier=FrontierConfig(enabled=true,schedule=FrontierRaidSchedule(
            ZoneOffset.UTC,LocalTime.now(ZoneOffset.UTC).minusHours(2),Duration.ofHours(1))))
        val action=IsPlayerActionAllowed(mockk(),mockk(),lookup,states,resolver,config)
        assertIs<IsPlayerActionAllowedResult.Denied>(action.execute(actor,world,Position2D(0,0),PlayerActionType.BREAK_BLOCK))
        claim.teamId=null
        assertIs<IsPlayerActionAllowedResult.Allowed>(action.execute(actor,world,Position2D(0,0),PlayerActionType.BREAK_BLOCK))
    }
}
