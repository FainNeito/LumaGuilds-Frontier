package net.lumalyte.lg.interaction.menus.guild

import io.mockk.*
import net.lumalyte.lg.application.services.MemberService
import net.lumalyte.lg.domain.entities.RankPermission
import net.lumalyte.lg.interaction.listeners.FrontierTheme
import net.lumalyte.lg.interaction.menus.MenuFactory
import net.lumalyte.lg.interaction.menus.MenuNavigator
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext

internal class FrontierMemberPromotionTest : GuildRankMenuFixture() {
    private fun checkRoute(allowed: Boolean, present: Boolean, frontier: Boolean, opens: Boolean) {
        val members = GlobalContext.get().get<MemberService>()
        val factory = GlobalContext.get().get<MenuFactory>()
        val navigator = mockk<MenuNavigator>(relaxed = true)
        every { members.hasPermission(player.uniqueId, guild.id, RankPermission.MANAGE_MEMBERS) } returns allowed
        every { members.getMember(member.playerId, guild.id) } returns if (present) member else null
        val previous = FrontierTheme.enabled
        try {
            FrontierTheme.enabled = frontier
            GuildMemberListMenu(navigator, player, guild).openMemberRank(member.playerId)
            verify(exactly = if (opens) 1 else 0) { factory.createGuildMemberRankMenu(navigator, player, guild, member) }
            verify(exactly = if (opens) 1 else 0) { navigator.openMenu(any()) }
        } finally { FrontierTheme.enabled = previous }
    }
    @Test fun `manager opens existing rank selection through roster`() = checkRoute(true, true, true, true)
    @Test fun `revoked management permission keeps roster read only`() = checkRoute(false, true, true, false)
    @Test fun `departed member cannot open stale rank selection`() = checkRoute(true, false, true, false)
    @Test fun `SMP roster remains read only`() = checkRoute(true, true, false, false)
}
