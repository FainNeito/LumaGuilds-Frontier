package net.lumalyte.lg.interaction.listeners

import io.mockk.*
import net.lumalyte.lg.application.services.MemberService
import net.lumalyte.lg.domain.entities.Member
import net.lumalyte.lg.infrastructure.services.FrontierVaultLossInbox
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class FrontierVaultLossNotificationsTest {
    @TempDir lateinit var dir: Path
    @Test fun `offline members get independent notice on join and online members are not queued`() {
        val plugin=mockk<JavaPlugin>(); val server=mockk<Server>(); val scheduler=mockk<BukkitScheduler>()
        val members=mockk<MemberService>(); val inbox=FrontierVaultLossInbox(dir.resolve("notices.yml").toFile())
        val guild=UUID.randomUUID(); val offline=UUID.randomUUID(); val online=UUID.randomUUID()
        val player=mockk<Player>(relaxed=true); val present=mockk<Player>()
        every { plugin.server } returns server
        every { server.scheduler } returns scheduler
        every { server.getPlayer(offline) } returns null
        every { server.getPlayer(online) } returns present
        every { present.isOnline } returns true
        every { player.isOnline } returns true
        every { player.uniqueId } returns offline
        every { members.getGuildMembers(guild) } returns setOf(Member(offline,guild,UUID.randomUUID(),Instant.now()),Member(online,guild,UUID.randomUUID(),Instant.now()))
        val task=slot<Runnable>()
        every { scheduler.runTaskLater(plugin,capture(task),40L) } returns mockk<BukkitTask>()
        val listener=FrontierVaultLossNotifications(plugin,members,inbox)
        listener.record(guild,"Red Keep","Raider")
        assertEquals("Raider",inbox.pending(offline).single().attacker)
        assertTrue(inbox.pending(online).isEmpty())
        listener.onJoin(PlayerJoinEvent(player,Component.empty()))
        assertEquals(1,inbox.pending(offline).size)
        task.captured.run()
        val message=slot<Component>()
        verify(exactly=1) { player.sendMessage(capture(message)) }
        val text=PlainTextComponentSerializer.plainText().serialize(message.captured)
        assertTrue(text.contains("Red Keep"))
        assertTrue(text.contains("Raider"))
        assertTrue(text.contains("claims were lost"))
        assertTrue(inbox.pending(offline).isEmpty())
    }

    @Test fun `disconnect before delayed delivery leaves the notice queued`() {
        val plugin=mockk<JavaPlugin>(); val server=mockk<Server>(); val scheduler=mockk<BukkitScheduler>()
        val members=mockk<MemberService>(); val inbox=FrontierVaultLossInbox(dir.resolve("pending.yml").toFile())
        val id=UUID.randomUUID(); val player=mockk<Player>(relaxed=true)
        every { plugin.server } returns server
        every { server.scheduler } returns scheduler
        every { player.uniqueId } returns id
        every { player.isOnline } returns false
        val task=slot<Runnable>()
        every { scheduler.runTaskLater(plugin,capture(task),40L) } returns mockk<BukkitTask>()
        inbox.record(setOf(id),"Red Keep",null,Instant.now().toString())
        FrontierVaultLossNotifications(plugin,members,inbox).onJoin(PlayerJoinEvent(player,Component.empty()))
        task.captured.run()
        assertEquals(1,inbox.pending(id).size)
        verify(exactly=0) { player.sendMessage(any<Component>()) }
    }
}
